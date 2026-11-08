package com.pyeonit.admin.community;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Configuration(value = "adminCommunityRetentionScheduledTasks", proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "pyeonit.admin.community",
    name = {"scheduling-enabled", "retention-cleanup-enabled"},
    havingValue = "true", matchIfMissing = true)
class RetentionScheduledTasks {
  private final JdbcTemplate db;
  private final Clock clock;
  private final int reportDays;
  private final int sanctionDays;
  private final int batchSize;

  RetentionScheduledTasks(JdbcTemplate db, Clock clock,
      @Value("${pyeonit.admin.community.handled-report-retention-days:365}") int reportDays,
      @Value("${pyeonit.admin.community.ended-sanction-retention-days:365}") int sanctionDays,
      @Value("${pyeonit.admin.community.retention-cleanup-batch-size:500}") int batchSize) {
    if (reportDays <= 0 || sanctionDays <= 0 || batchSize <= 0)
      throw new IllegalArgumentException("Retention and batch size must be positive");
    this.db = db;
    this.clock = clock;
    this.reportDays = reportDays;
    this.sanctionDays = sanctionDays;
    this.batchSize = batchSize;
  }

  @Scheduled(cron = "${pyeonit.admin.community.retention-cleanup-cron:0 30 4 * * *}", zone = "UTC")
  @Transactional
  public void deleteExpiredRecords() {
    var now = OffsetDateTime.now(clock);
    // Leave all withdrawn-user relationships to B7's withdrawal-based retention policy.
    int reports = db.update("""
        delete from reports where id in (
          select r.id from reports r
          where r.status<>'PENDING' and r.handled_at < ? and not exists (
            select 1 from users u where u.status='WITHDRAWN'
            and (r.reporter_id=u.id
              or (r.target_type='USER' and r.target_id=u.id)
              or (r.target_type='POST' and exists (
                select 1 from posts p where p.id=r.target_id and p.author_id=u.id))
              or (r.target_type='COMMENT' and exists (
                select 1 from comments c where c.id=r.target_id and c.author_id=u.id)))
          ) order by r.id limit ? for update of r skip locked
        )
        """, now.minusDays(reportDays), batchSize);
    if (reports == batchSize) return;
    // releaseSanction stores the early release time in ends_at. WARNING without ends_at has
    // no active period; its issue time (starts_at) is the retention origin.
    db.update("""
        delete from sanctions where id in (
          select s.id from sanctions s join users u on u.id=s.user_id
          where u.status<>'WITHDRAWN'
            and (s.ends_at < ? or (s.type='WARNING' and s.ends_at is null and s.starts_at < ?))
          order by s.id limit ? for update of s skip locked
        )
        """, now.minusDays(sanctionDays), now.minusDays(sanctionDays), batchSize - reports);
  }
}
