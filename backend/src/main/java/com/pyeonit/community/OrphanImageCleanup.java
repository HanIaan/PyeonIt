package com.pyeonit.community;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class OrphanImageCleanup {
  static final int BATCH_SIZE = 200;
  private static final Logger log = LoggerFactory.getLogger(OrphanImageCleanup.class);
  private final JdbcTemplate db;
  private final ImageStorage storage;
  private final Clock clock;
  private final Duration retention;
  private final TransactionTemplate transaction;

  OrphanImageCleanup(
      JdbcTemplate db,
      ImageStorage storage,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${pyeonit.community.orphan-image-retention:24h}") Duration retention) {
    if (retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("Image retention must be positive");
    }
    this.db = db;
    this.storage = storage;
    this.clock = clock;
    this.retention = retention;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  void cleanup() {
    cleanup(Timestamp.from(clock.instant().minus(retention)), BATCH_SIZE, false);
  }

  void cleanupDeleted(Duration retention, int batchSize) {
    cleanup(Timestamp.from(clock.instant().minus(retention)), batchSize, true);
  }

  private void cleanup(Timestamp cutoff, int batchSize, boolean deleted) {
    String from = deleted
        ? " from post_images i join posts p on p.id=i.post_id"
        : " from post_images i";
    String eligible = deleted
        ? "p.status='DELETED' and p.updated_at < ?"
        : "i.post_id is null and i.created_at < ?";
    var candidates = db.queryForList(
        "select i.id" + from + " where " + eligible
            + (deleted ? " order by p.updated_at,i.id limit ?" : " order by i.created_at,i.id limit ?"),
        Long.class, cutoff, batchSize);
    for (long id : candidates) {
      try {
        transaction.executeWithoutResult(status -> {
          // CommunityService.write locks the same row before attaching it. Recheck eligibility
          // under that lock; another worker or an attachment holding it is skipped this cycle.
          var keys = db.queryForList(
              "select i.storage_key" + from + " where i.id=? and " + eligible
                  + (deleted ? " for update of i,p skip locked" : " for update of i skip locked"),
              String.class, id, cutoff);
          if (keys.isEmpty()) return;
          try {
            storage.delete(keys.getFirst());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          // Eligibility and both the image and its deleted post remain locked until commit.
          db.update("delete from post_images where id=?", id);
        });
      } catch (RuntimeException e) {
        // Keep failures isolated and retry next cycle. Do not log storage keys or exception
        // messages, which may contain provider credentials or signed URLs.
        log.warn("Image cleanup failed for image {}; retrying next cycle", id);
      }
    }
  }
}
