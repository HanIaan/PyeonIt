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

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "pyeonit.admin.community",
    name = {"scheduling-enabled", "withdrawn-cleanup-enabled"},
    havingValue = "true",
    matchIfMissing = true)
class WithdrawnRecordScheduledTasks {
  private final JdbcTemplate db;
  private final Clock clock;
  private final int retentionDays;
  private final int batchSize;

  WithdrawnRecordScheduledTasks(
      JdbcTemplate db,
      Clock clock,
      @Value("${pyeonit.admin.community.withdrawn-retention-days:365}") int retentionDays,
      @Value("${pyeonit.admin.community.withdrawn-cleanup-batch-size:500}") int batchSize) {
    if (retentionDays <= 0 || batchSize <= 0) {
      throw new IllegalArgumentException("Withdrawal retention and batch size must be positive");
    }
    this.db = db;
    this.clock = clock;
    this.retentionDays = retentionDays;
    this.batchSize = batchSize;
  }

  @Scheduled(cron = "${pyeonit.admin.community.withdrawn-cleanup-cron:0 0 4 * * *}", zone = "UTC")
  @Transactional
  public void deleteExpiredRecords() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(retentionDays);
    // The budget counts deleted records, not users: even one user's history is bounded.
    int deletedReports = db.update("""
        delete from reports where id in (
          select r.id from reports r
          where exists (
            select 1 from users u where u.status='WITHDRAWN' and u.withdrawn_at < ?
            and (r.reporter_id=u.id
              or (r.target_type='USER' and r.target_id=u.id)
              or (r.target_type='POST' and exists (
                select 1 from posts p where p.id=r.target_id and p.author_id=u.id))
              or (r.target_type='COMMENT' and exists (
                select 1 from comments c where c.id=r.target_id and c.author_id=u.id)))
          ) order by r.id limit ? for update of r skip locked
        )
        """, cutoff, batchSize);
    if (deletedReports == batchSize) return;
    db.update("""
        delete from sanctions where id in (
          select s.id from sanctions s join users u on u.id=s.user_id
          where u.status='WITHDRAWN' and u.withdrawn_at < ?
          order by s.id limit ? for update of s skip locked
        )
        """, cutoff, batchSize - deletedReports);
  }
}
