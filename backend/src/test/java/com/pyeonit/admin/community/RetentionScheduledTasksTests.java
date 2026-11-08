package com.pyeonit.admin.community;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "pyeonit.community.retention-cleanup-enabled=false")
@Import(TestcontainersConfiguration.class)
class RetentionScheduledTasksTests {
  static final OffsetDateTime NOW = OffsetDateTime.parse("2030-11-08T04:30:00Z");
  static final OffsetDateTime CUTOFF = NOW.minusDays(365);
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;
  long member, other, withdrawn;
  RetentionScheduledTasks tasks;

  @BeforeEach
  void setup() {
    db.execute("truncate users, sanction_blocklist cascade");
    member = user("ACTIVE"); other = user("SUSPENDED"); withdrawn = user("WITHDRAWN");
    tasks = cleaner(365, 365, 500);
  }

  RetentionScheduledTasks cleaner(int reports, int sanctions, int batch) {
    return new RetentionScheduledTasks(db, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), reports, sanctions, batch);
  }

  void clean() {
    new TransactionTemplate(transactions).executeWithoutResult(s -> tasks.deleteExpiredRecords());
  }

  long user(String status) {
    return db.queryForObject("insert into users(nickname,status) values (?,?) returning id", Long.class,
        UUID.randomUUID().toString().substring(0, 12), status);
  }

  long report(long reporter, String targetType, long target, String status, OffsetDateTime handledAt) {
    return db.queryForObject("""
        insert into reports(reporter_id,target_type,target_id,reason,status,handled_at)
        values (?,?,?,'SPAM',?,?) returning id
        """, Long.class, reporter, targetType, target, status, handledAt);
  }

  long sanction(long user, String type, OffsetDateTime starts, OffsetDateTime ends) {
    return db.queryForObject("""
        insert into sanctions(user_id,type,reason,starts_at,ends_at,created_by)
        values (?,?,'B8',?,?,?) returning id
        """, Long.class, user, type, starts, ends, member);
  }

  @Test
  void strictReportBoundariesNeverDeletePendingOrUnprocessedAndLeaveWithdrawnRelationshipsToB7() {
    long expired = report(member, "USER", other, "RESOLVED", CUTOFF.minusNanos(1000));
    long dismissed = report(other, "USER", member, "DISMISSED", CUTOFF.minusNanos(1000));
    var kept = List.of(
        report(member, "POST", 100001, "RESOLVED", CUTOFF),
        report(member, "POST", 100002, "RESOLVED", CUTOFF.plusNanos(1000)),
        report(member, "POST", 100003, "PENDING", CUTOFF.minusDays(100)),
        report(member, "POST", 100004, "PENDING", null),
        report(member, "POST", 100005, "RESOLVED", null),
        report(withdrawn, "USER", other, "RESOLVED", CUTOFF.minusDays(100)),
        report(member, "USER", withdrawn, "RESOLVED", CUTOFF.minusDays(100)));
    long post = db.queryForObject("""
        insert into posts(board_id,author_id,title,body) select id,?,'B8','body' from boards limit 1 returning id
        """, Long.class, withdrawn);
    long comment = db.queryForObject("insert into comments(post_id,author_id,body) values (?,?,'body') returning id",
        Long.class, post, withdrawn);
    long postReport = report(member, "POST", post, "RESOLVED", CUTOFF.minusDays(100));
    long commentReport = report(member, "COMMENT", comment, "RESOLVED", CUTOFF.minusDays(100));
    db.update("insert into sanction_blocklist(identity_hash,expires_at) values (?,?)", "a".repeat(64), NOW.plusDays(1));
    clean();
    assertThat(db.queryForList("select id from reports", Long.class)).containsAll(kept)
        .contains(postReport, commentReport).doesNotContain(expired, dismissed).hasSize(9);
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Integer.class)).isOne();
    clean();
    assertThat(db.queryForObject("select count(*) from reports", Integer.class)).isEqualTo(9);
  }

  @Test
  void endedSanctionBoundaryWarningsEarlyReleaseAndActiveOrIndefiniteSanctions() {
    var expired = List.of(
        sanction(member, "SUSPEND", CUTOFF.minusDays(2), CUTOFF.minusNanos(1000)),
        sanction(other, "BAN", CUTOFF.minusDays(2), CUTOFF.minusNanos(1000)),
        sanction(member, "WARNING", CUTOFF.minusNanos(1000), null));
    var kept = List.of(
        sanction(member, "SUSPEND", CUTOFF.minusDays(2), CUTOFF),
        sanction(member, "SUSPEND", CUTOFF.minusDays(2), CUTOFF.plusNanos(1000)),
        sanction(member, "WARNING", CUTOFF, null),
        sanction(member, "WARNING", CUTOFF.plusNanos(1000), null),
        sanction(member, "SUSPEND", CUTOFF.minusDays(1000), NOW.plusNanos(1000)),
        sanction(member, "SUSPEND", CUTOFF.minusDays(1000), null),
        sanction(member, "BAN", CUTOFF.minusDays(1000), null),
        sanction(withdrawn, "WARNING", CUTOFF.minusDays(1000), null),
        sanction(member, "SUSPEND", CUTOFF.minusDays(1000), NOW));
    // releaseSanction writes ends_at=now. A recently released old sanction must be retained.
    long released = sanction(member, "BAN", CUTOFF.minusDays(1000), NOW.minusDays(1));
    clean();
    assertThat(db.queryForList("select id from sanctions", Long.class)).containsAll(kept)
        .contains(released).doesNotContainAnyElementsOf(expired).hasSize(10);
  }

  @Test
  void configurableRetentionAndSharedBudgetDeferRemainder() {
    for (int i = 0; i < 3; i++) report(member, "POST", 100 + i, "RESOLVED", NOW.minusDays(2));
    long s = sanction(member, "WARNING", NOW.minusDays(2), null);
    clean();
    assertThat(count()).isEqualTo(4);
    tasks = cleaner(1, 3, 2);
    clean();
    assertThat(count()).isEqualTo(2);
    clean();
    assertThat(db.queryForObject("select id from sanctions", Long.class)).isEqualTo(s);
    tasks = cleaner(1, 1, 2);
    clean();
    assertThat(count()).isZero();
  }

  @Test
  void skipsLockedReportsAndSanctionsWithoutWaiting() throws Exception {
    long r = report(member, "USER", other, "RESOLVED", CUTOFF.minusSeconds(1));
    long s = sanction(member, "WARNING", CUTOFF.minusSeconds(1), null);
    try (var executor = Executors.newSingleThreadExecutor()) {
      new TransactionTemplate(transactions).executeWithoutResult(tx -> {
        db.queryForList("select id from reports where id=? for update", r);
        db.queryForList("select id from sanctions where id=? for update", s);
        try { executor.submit(this::clean).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new RuntimeException(e); }
        assertThat(count()).isEqualTo(2);
      });
    }
    clean();
    assertThat(count()).isZero();
  }

  @Test
  void schedulingCanBeDisabledAndInvalidSettingsFailFast() {
    for (String property : List.of("scheduling-enabled", "retention-cleanup-enabled"))
      new ApplicationContextRunner().withUserConfiguration(RetentionScheduledTasks.class)
          .withPropertyValues("pyeonit.admin.community." + property + "=false")
          .run(ctx -> assertThat(ctx).doesNotHaveBean(RetentionScheduledTasks.class));
    assertThatThrownBy(() -> cleaner(0, 365, 500)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cleaner(365, 0, 500)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cleaner(365, 365, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  long count() {
    return db.queryForObject("select (select count(*) from reports)+(select count(*) from sanctions)", Long.class);
  }
}
