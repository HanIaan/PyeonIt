package com.pyeonit.community;

import java.time.Clock;
import java.time.Duration;
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
@ConditionalOnProperty(prefix = "pyeonit.community", name = "retention-cleanup-enabled",
    havingValue = "true", matchIfMissing = true)
class RetentionScheduledTasks {
  private final JdbcTemplate db;
  private final Clock clock;
  private final OrphanImageCleanup images;
  private final int bodyDays;
  private final int imageDays;
  private final int batchSize;

  RetentionScheduledTasks(JdbcTemplate db, Clock clock, OrphanImageCleanup images,
      @Value("${pyeonit.community.deleted-body-retention-days:30}") int bodyDays,
      @Value("${pyeonit.community.deleted-image-retention-days:30}") int imageDays,
      @Value("${pyeonit.community.retention-cleanup-batch-size:200}") int batchSize) {
    if (bodyDays <= 0 || imageDays <= 0 || batchSize <= 0)
      throw new IllegalArgumentException("Retention and batch size must be positive");
    this.db = db;
    this.clock = clock;
    this.images = images;
    this.bodyDays = bodyDays;
    this.imageDays = imageDays;
    this.batchSize = batchSize;
  }

  @Scheduled(cron = "${pyeonit.community.retention-cleanup-cron:0 0 3 * * *}", zone = "UTC")
  @Transactional
  public void clearExpiredBodies() {
    var cutoff = OffsetDateTime.now(clock).minusDays(bodyDays);
    // updated_at is the deletion timestamp: counter updates leave it unchanged and edits/hides
    // require PUBLISHED. Never change it while clearing the title/body (image retention also uses it).
    int posts = db.update("""
        update posts set title='',body='' where id in (
          select id from posts where status='DELETED' and (body<>'' or title<>'') and updated_at < ?
          order by id limit ? for update skip locked
        )
        """, cutoff, batchSize);
    if (posts == batchSize) return;
    db.update("""
        update comments set body='' where id in (
          select id from comments where status='DELETED' and body<>'' and updated_at < ?
          order by id limit ? for update skip locked
        )
        """, cutoff, batchSize - posts);
  }

  @Scheduled(cron = "${pyeonit.community.retention-cleanup-cron:0 0 3 * * *}", zone = "UTC")
  public void deleteExpiredImages() {
    images.cleanupDeleted(Duration.ofDays(imageDays), batchSize);
  }
}
