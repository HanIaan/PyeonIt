package com.pyeonit.auth;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "pyeonit.push.scheduling-enabled=false")
@Import(TestcontainersConfiguration.class)
class WithdrawalCleanupTests {
  @Autowired JdbcTemplate db;
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  long member;
  long other;
  long post;

  @BeforeEach
  void setup() {
    db.execute("truncate users, sanction_blocklist cascade");
    member = newUser();
    other = newUser();
    long university = db.queryForObject("""
        insert into universities(name,region) values (?,'SEOUL') returning id
        """, Long.class, "B7" + UUID.randomUUID());
    post = db.queryForObject("""
        insert into posts(board_id,author_id,title,body)
        select id,?,'B7','body' from boards limit 1 returning id
        """, Long.class, other);
    for (long id : new long[] {member, other}) {
      db.update("insert into social_accounts(user_id,provider,provider_user_id) values (?,'KAKAO',?)",
          id, UUID.randomUUID().toString());
      db.update("insert into favorite_universities(user_id,university_id) values (?,?)", id, university);
      db.update("insert into notification_settings(user_id) values (?)", id);
      db.update("insert into notification_log(user_id,kind,ref_key) values (?,'NEW_NOTICE',1)", id);
      db.update("insert into push_subscriptions(user_id,endpoint,p256dh,auth) values (?,?,?,?)",
          id, "https://push.example.invalid/" + UUID.randomUUID(), "test", "test");
      db.update("insert into post_likes(post_id,user_id) values (?,?)", post, id);
      db.update("insert into sanctions(user_id,type,reason,created_by) values (?,'BAN','B7',?)", id, other);
    }
    db.update("insert into reports(reporter_id,target_type,target_id,reason) values (?,'POST',?,'SPAM')",
        other, post);
  }

  long newUser() {
    return db.queryForObject("insert into users(nickname) values (?) returning id",
        Long.class, UUID.randomUUID().toString().substring(0, 12));
  }

  @AfterEach
  void removeFailureTrigger() {
    db.execute("drop trigger if exists b7_fail_cleanup on notification_log");
    db.execute("drop function if exists b7_fail_cleanup()");
  }

  @Test
  void withdrawDeletesOnlyPersonalSettingsAndTriggerDeletesSubscriptions() {
    auth.withdraw(users.findById(member).orElseThrow());

    for (String table : new String[] {"favorite_universities", "notification_settings", "notification_log",
        "push_subscriptions", "social_accounts"}) {
      assertThat(count(table, member)).as(table).isZero();
      assertThat(count(table, other)).as("other " + table).isOne();
    }
    assertThat(count("post_likes", member)).isOne();
    assertThat(count("post_likes", other)).isOne();
    assertThat(count("sanctions", member)).isOne();
    assertThat(count("sanctions", other)).isOne();
    assertThat(db.queryForObject("select count(*) from posts where id=?", Long.class, post)).isOne();
    assertThat(db.queryForObject("select count(*) from reports where reporter_id=?", Long.class, other)).isOne();
    var row = db.queryForMap("select status,nickname,withdrawn_at from users where id=?", member);
    assertThat(row.get("status")).isEqualTo("WITHDRAWN");
    assertThat(row.get("nickname")).isNull();
    assertThat(row.get("withdrawn_at")).isNotNull();
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Long.class)).isOne();
  }

  @Test
  void lastCleanupFailureRollsBackWithdrawalAndAllEarlierDeletes() {
    db.execute("""
        create function b7_fail_cleanup() returns trigger language plpgsql as $$
        begin raise exception 'B7 test cleanup failure'; end; $$
        """);
    db.execute("""
        create trigger b7_fail_cleanup before delete on notification_log
        for each row execute function b7_fail_cleanup()
        """);

    assertThatThrownBy(() -> auth.withdraw(users.findById(member).orElseThrow()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    for (String table : new String[] {"favorite_universities", "notification_settings", "notification_log",
        "push_subscriptions", "social_accounts", "post_likes", "sanctions"}) {
      assertThat(count(table, member)).as(table).isOne();
      assertThat(count(table, other)).as("other " + table).isOne();
    }
    var row = db.queryForMap("select status,nickname,withdrawn_at from users where id=?", member);
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(row.get("nickname")).isNotNull();
    assertThat(row.get("withdrawn_at")).isNull();
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Long.class)).isZero();
  }

  long count(String table, long userId) {
    return db.queryForObject("select count(*) from " + table + " where user_id=?", Long.class, userId);
  }
}
