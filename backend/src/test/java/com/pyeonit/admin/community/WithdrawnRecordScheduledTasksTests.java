package com.pyeonit.admin.community;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "pyeonit.admin.community.scheduling-enabled=true",
    "pyeonit.admin.community.expiration-interval-ms=86400000",
    "pyeonit.admin.community.blocklist-cleanup-cron=-",
    "pyeonit.admin.community.withdrawn-cleanup-cron=-",
    "pyeonit.push.scheduling-enabled=false"})
@Import({TestcontainersConfiguration.class, WithdrawnRecordScheduledTasksTests.TimeConfig.class})
class WithdrawnRecordScheduledTasksTests {
  static final OffsetDateTime NOW = OffsetDateTime.parse("2030-11-08T04:00:00Z");
  static final OffsetDateTime CUTOFF = NOW.minusDays(365);

  @TestConfiguration(proxyBeanMethods = false)
  static class TimeConfig {
    @Bean @Primary Clock fixedClock() {
      return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    }
  }

  @Autowired JdbcTemplate db;
  @Autowired WithdrawnRecordScheduledTasks tasks;
  long other;

  @BeforeEach
  void setup() {
    db.execute("truncate users, sanction_blocklist cascade");
    other = user("ACTIVE", null);
  }

  long user(String status, OffsetDateTime withdrawnAt) {
    return db.queryForObject("insert into users(nickname,status,withdrawn_at) values (?,?,?) returning id",
        Long.class, UUID.randomUUID().toString().substring(0, 12), status, withdrawnAt);
  }

  long post(long author) {
    return db.queryForObject("""
        insert into posts(board_id,author_id,title,body,status)
        select id,?,'B7','body','DELETED' from boards limit 1 returning id
        """, Long.class, author);
  }

  long comment(long author, long post) {
    return db.queryForObject("insert into comments(post_id,author_id,body,status) values (?,?,'B7','HIDDEN') returning id",
        Long.class, post, author);
  }

  long report(long reporter, String type, long target) {
    return db.queryForObject("""
        insert into reports(reporter_id,target_type,target_id,reason) values (?,?,?,'SPAM') returning id
        """, Long.class, reporter, type, target);
  }

  long sanction(long target, long creator) {
    return db.queryForObject("""
        insert into sanctions(user_id,type,reason,created_by) values (?,'WARNING','B7',?) returning id
        """, Long.class, target, creator);
  }

  @Test
  void strictRetentionBoundaryDeletesEveryReportRelationshipAndOnlyTargetedSanctions() {
    long expired = user("WITHDRAWN", CUTOFF.minusNanos(1000));
    long exact = user("WITHDRAWN", CUTOFF);
    long recent = user("WITHDRAWN", CUTOFF.plusNanos(1000));
    long active = user("ACTIVE", CUTOFF.minusDays(1));
    long suspended = user("SUSPENDED", CUTOFF.minusDays(1));
    long noTimestamp = user("WITHDRAWN", null);
    long otherPost = post(other);
    long otherComment = comment(other, otherPost);
    long expiredPost = post(expired);
    long expiredComment = comment(expired, otherPost);
    var deleted = List.of(report(expired, "USER", other), report(other, "USER", expired),
        report(other, "POST", expiredPost), report(other, "COMMENT", expiredComment));
    long deletedSanction = sanction(expired, other);
    var unrelated = List.of(report(other, "POST", otherPost), report(other, "COMMENT", otherComment));
    // Handling reports or creating sanctions as an admin is not a deletion target.
    db.update("update reports set handled_by=?,status='RESOLVED' where id=?", expired, unrelated.getFirst());
    long unrelatedSanction = sanction(other, expired);
    for (long id : new long[] {exact, recent, active, suspended, noTimestamp}) {
      long ownPost = post(id);
      long ownComment = comment(id, otherPost);
      report(id, "USER", other);
      report(other, "USER", id);
      report(other, "POST", ownPost);
      report(other, "COMMENT", ownComment);
      sanction(id, other);
    }
    long university = db.queryForObject("insert into universities(name,region) values (?,'SEOUL') returning id",
        Long.class, "B7" + UUID.randomUUID());
    db.update("insert into favorite_universities(user_id,university_id) values (?,?)", other, university);
    db.update("insert into post_likes(user_id,post_id) values (?,?)", expired, otherPost);
    db.update("insert into sanction_blocklist(identity_hash,expires_at) values (?,?)", "a".repeat(64), NOW.plusDays(1));
    long posts = count("posts");
    long comments = count("comments");
    long users = count("users");

    tasks.deleteExpiredRecords();

    assertThat(db.queryForList("select id from reports", Long.class))
        .doesNotContainAnyElementsOf(deleted).containsAll(unrelated).hasSize(22);
    assertThat(db.queryForList("select id from sanctions", Long.class))
        .doesNotContain(deletedSanction).contains(unrelatedSanction).hasSize(6);
    assertThat(count("posts")).isEqualTo(posts);
    assertThat(count("comments")).isEqualTo(comments);
    assertThat(count("users")).isEqualTo(users);
    assertThat(count("post_likes")).isOne();
    assertThat(count("favorite_universities")).isOne();
    assertThat(count("sanction_blocklist")).isOne();
    tasks.deleteExpiredRecords();
    assertThat(count("reports")).isEqualTo(22);
    assertThat(count("sanctions")).isEqualTo(6);
  }

  @ParameterizedTest
  @ValueSource(ints = {498, 501})
  void sharedRowBudgetLeavesRemainingRecordsForNextInvocation(int reports) {
    long expired = user("WITHDRAWN", CUTOFF.minusSeconds(1));
    db.update("""
        insert into reports(reporter_id,target_type,target_id,reason)
        select ?,'USER',n,'SPAM' from generate_series(1,?) n
        """, expired, reports);
    for (int i = 0; i < 3; i++) sanction(expired, other);
    long unrelated = report(other, "USER", other);
    long unrelatedSanction = sanction(other, other);

    tasks.deleteExpiredRecords();

    assertThat(count("reports") + count("sanctions")).isEqualTo(reports + 3 + 2 - 500);
    tasks.deleteExpiredRecords();
    assertThat(db.queryForList("select id from reports", Long.class)).containsExactly(unrelated);
    assertThat(db.queryForList("select id from sanctions", Long.class)).containsExactly(unrelatedSanction);
  }

  @Test
  void schedulingCanBeDisabledByEitherSwitch() {
    for (String property : List.of("scheduling-enabled", "withdrawn-cleanup-enabled")) {
      new ApplicationContextRunner().withUserConfiguration(WithdrawnRecordScheduledTasks.class)
          .withPropertyValues("pyeonit.admin.community." + property + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(WithdrawnRecordScheduledTasks.class));
    }
    assertThatThrownBy(() -> new WithdrawnRecordScheduledTasks(db, Clock.systemUTC(), 0, 500))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WithdrawnRecordScheduledTasks(db, Clock.systemUTC(), 365, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  long count(String table) {
    return db.queryForObject("select count(*) from " + table, Long.class);
  }
}
