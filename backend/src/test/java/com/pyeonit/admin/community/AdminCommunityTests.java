package com.pyeonit.admin.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AdminCommunityTests.TimeConfig.class})
class AdminCommunityTests {
  static final Instant START = Instant.parse("2030-02-01T00:00:00Z");

  static class MutableClock extends Clock {
    Instant instant = START;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TimeConfig {
    @Bean
    @org.springframework.context.annotation.Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired MutableClock clock;
  @Autowired AdminCommunityService service;
  @Autowired JdbcSanctionBlocklist blocklist;
  @Autowired BlocklistHasher hasher;
  long admin;
  long member;

  @BeforeEach
  void setup() {
    clock.instant = START;
    db.execute("truncate users, sanction_blocklist cascade");
    admin = newUser("ADMIN");
    member = newUser("USER");
  }

  long newUser(String role) {
    return db.queryForObject(
        "insert into users(nickname,role) values (?,?) returning id",
        Long.class,
        "n" + UUID.randomUUID().toString().substring(0, 10),
        role);
  }

  RequestPostProcessor token() throws Exception {
    Cookie cookie = mvc.perform(get("/api/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    return request -> {
      request.setCookies(cookie);
      request.addHeader("X-XSRF-TOKEN", cookie.getValue());
      return request;
    };
  }

  ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(request.with(user(Long.toString(admin)).roles("ADMIN")).with(token()));
  }

  long sanction(String type, String end) throws Exception {
    send(
            post("/api/admin/sanctions"),
            "{\"userId\":"
                + member
                + ",\"type\":\""
                + type
                + "\",\"reason\":\"규칙 위반\",\"endsAt\":"
                + (end == null ? "null" : "\"" + end + "\"")
                + "}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value(type))
        .andExpect(jsonPath("$.active").value(!type.equals("WARNING")));
    return db.queryForObject("select max(id) from sanctions where user_id=?", Long.class, member);
  }

  String state() {
    return db.queryForObject("select status from users where id=?", String.class, member);
  }

  void social(String provider, String raw) {
    db.update(
        "insert into social_accounts(user_id,provider,provider_user_id) values (?,?,?)",
        member,
        provider,
        raw);
  }

  void withdraw() throws Exception {
    mvc.perform(delete("/api/me").with(user(Long.toString(member))).with(token()))
        .andExpect(status().isNoContent());
    assertThat(state()).isEqualTo("WITHDRAWN");
  }

  ResultActions signup(String provider, String raw, String nickname) throws Exception {
    var pending =
        UsernamePasswordAuthenticationToken.authenticated(
            "pending:" + provider + ":" + raw, null, List.of());
    return mvc.perform(
        post("/api/auth/signup")
            .with(authentication(pending))
            .with(token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"" + nickname + "\"}"));
  }

  @Test
  void suspendWithdrawalStoresOnlyHmacAndBlocksBothOldAndNewPendingSessions() throws Exception {
    String raw = "private-social-" + UUID.randomUUID();
    String google = "private-google-" + UUID.randomUUID();
    social("KAKAO", raw);
    social("GOOGLE", google);
    // A pending identity can have been issued before the withdrawal; signup must recheck it.
    var stalePending =
        UsernamePasswordAuthenticationToken.authenticated("pending:kakao:" + raw, null, List.of());
    sanction("SUSPEND", "2030-02-10T00:00:00Z");
    withdraw();
    assertThat(db.queryForList("select identity_hash from sanction_blocklist", String.class))
        .containsExactlyInAnyOrder(hasher.hash("kakao", raw), hasher.hash("google", google));
    assertThat(db.queryForObject("select count(*) from social_accounts", Long.class)).isZero();
    // Search every application table's visible row data, not just the blocklist table.
    for (String table :
        db.queryForList(
            "select tablename from pg_tables where schemaname='public'", String.class)) {
      for (String row :
          db.queryForList(
              "select row_to_json(t)::text from \"" + table.replace("\"", "\"\"") + "\" t",
              String.class)) assertThat(row).doesNotContain(raw, google);
    }
    signup("kakao", raw, "가입차단1")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"))
        .andExpect(jsonPath("$.message").value("가입할 수 없는 계정입니다"));
    signup("google", google, "가입차단2").andExpect(status().isForbidden());
    mvc.perform(
            post("/api/auth/signup")
                .with(authentication(stalePending))
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"가입차단3\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    service.expireSanctions();
    assertThat(state()).isEqualTo("WITHDRAWN");
  }

  @Test
  void warningAndExpiredSuspensionAllowSignupAfterWithdrawal() throws Exception {
    social("KAKAO", "warning-id");
    sanction("WARNING", null);
    assertThat(state()).isEqualTo("ACTIVE");
    withdraw();
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Long.class)).isZero();
    signup("kakao", "warning-id", "경고후재가입").andExpect(status().isCreated());
    member = newUser("USER");
    social("KAKAO", "expired-id");
    sanction("SUSPEND", "2030-02-02T00:00:00Z");
    clock.instant = START.plus(Duration.ofDays(1));
    // No expiration worker has run yet: withdrawal must use dates rather than stale users.status.
    withdraw();
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Long.class)).isZero();
    signup("kakao", "expired-id", "만료후재가입").andExpect(status().isCreated());
  }

  @Test
  void blocklistExpiresAtSanctionEndAndIndefiniteAtOneCalendarYear() throws Exception {
    social("KAKAO", "finite-id");
    sanction("SUSPEND", "2030-02-03T00:00:00Z");
    withdraw();
    member = newUser("USER");
    social("KAKAO", "infinite-id");
    sanction("BAN", null);
    withdraw();
    clock.instant = Instant.parse("2030-02-02T23:59:59Z");
    blocklist.deleteExpired();
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Long.class))
        .isEqualTo(2);
    clock.instant = Instant.parse("2030-02-03T00:00:00Z");
    blocklist.deleteExpired();
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Long.class)).isOne();
    signup("kakao", "finite-id", "유기정지만료").andExpect(status().isCreated());
    clock.instant = Instant.parse("2031-01-31T23:59:59Z");
    blocklist.deleteExpired();
    signup("kakao", "infinite-id", "차단아직유효").andExpect(status().isForbidden());
    clock.instant = Instant.parse("2031-02-01T00:00:00Z");
    blocklist.deleteExpired();
    assertThat(db.queryForObject("select count(*) from sanction_blocklist", Long.class)).isZero();
    signup("kakao", "infinite-id", "무기정지만료").andExpect(status().isCreated());
  }

  @Test
  void creationReleaseAndExpirationSynchronizeOverlappingSanctions() throws Exception {
    long first = sanction("SUSPEND", "2030-02-02T00:00:00Z");
    long second = sanction("BAN", null);
    assertThat(state()).isEqualTo("SUSPENDED");
    send(
            get("/api/admin/sanctions")
                .param("activeOnly", "true")
                .param("userId", Long.toString(member)),
            null)
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].id").value(second));
    send(post("/api/admin/sanctions/" + second + "/release"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.endsAt").isNotEmpty());
    assertThat(state()).isEqualTo("SUSPENDED");
    send(post("/api/admin/sanctions/" + second + "/release"), null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SANCTION_NOT_ACTIVE"));
    clock.instant = START.plus(Duration.ofDays(1));
    service.expireSanctions();
    assertThat(state()).isEqualTo("ACTIVE");
    send(post("/api/admin/sanctions/" + first + "/release"), null).andExpect(status().isConflict());
    long third = sanction("BAN", null);
    send(post("/api/admin/sanctions/" + third + "/release"), null).andExpect(status().isOk());
    assertThat(state()).isEqualTo("ACTIVE");
    long warning = sanction("WARNING", null);
    send(post("/api/admin/sanctions/" + warning + "/release"), null)
        .andExpect(status().isConflict());
    send(get("/api/admin/sanctions").param("activeOnly", "true"), null)
        .andExpect(jsonPath("$.totalElements").value(0));
    withdraw();
    sanction("BAN", null);
    service.expireSanctions();
    assertThat(state()).isEqualTo("WITHDRAWN");
  }

  @Test
  void reportHandlingIsAtomicAndHidesPostOrComment() throws Exception {
    long post =
        db.queryForObject(
            "insert into posts(board_id,author_id,title,body) select id,?,'제목','본문' from boards"
                + " limit 1 returning id",
            Long.class,
            member);
    long comment =
        db.queryForObject(
            "insert into comments(post_id,author_id,body) values (?,?,'댓글') returning id",
            Long.class,
            post,
            member);
    for (String target : List.of("POST", "COMMENT", "USER")) {
      long targetId = target.equals("POST") ? post : target.equals("COMMENT") ? comment : member;
      long report =
          db.queryForObject(
              "insert into reports(reporter_id,target_type,target_id,reason) values (?,?,?,'SPAM')"
                  + " returning id",
              Long.class,
              admin,
              target,
              targetId);
      send(get("/api/admin/reports").param("targetType", target), null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].target.author.id").value(member));
      send(
              post("/api/admin/reports/" + report + "/handle"),
              "{\"action\":\"RESOLVE\",\"hideTarget\":true}")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("RESOLVED"))
          .andExpect(jsonPath("$.handledBy.id").value(admin))
          .andExpect(jsonPath("$.handledAt").isNotEmpty());
      send(post("/api/admin/reports/" + report + "/handle"), "{\"action\":\"DISMISS\"}")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("REPORT_ALREADY_HANDLED"));
    }
    assertThat(db.queryForObject("select status from posts where id=?", String.class, post))
        .isEqualTo("HIDDEN");
    assertThat(db.queryForObject("select status from comments where id=?", String.class, comment))
        .isEqualTo("HIDDEN");
    long dismissed =
        db.queryForObject(
            "insert into reports(reporter_id,target_type,target_id,reason) values"
                + " (?,'POST',?,'OTHER') returning id",
            Long.class,
            member,
            post);
    send(post("/api/admin/reports/" + dismissed + "/handle"), "{\"action\":\"DISMISS\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISMISSED"));
  }

  @Test
  void allAdminOperationsRequireAdminAndValidateInput() throws Exception {
    for (var request :
        List.of(
            get("/api/admin/reports"),
            get("/api/admin/sanctions"),
            post("/api/admin/reports/1/handle"),
            post("/api/admin/sanctions"),
            post("/api/admin/sanctions/1/release"))) {
      mvc.perform(request.with(token())).andExpect(status().isUnauthorized());
      mvc.perform(request.with(user(Long.toString(member))).with(token()))
          .andExpect(status().isForbidden());
    }
    for (String body :
        List.of(
            "{\"type\":\"BAN\",\"reason\":\" \"}",
            "{\"type\":\"SUSPEND\",\"reason\":\"사유\"}",
            "{\"type\":\"SUSPEND\",\"reason\":\"사유\",\"endsAt\":\"2030-02-01T00:00:00Z\"}",
            "{\"type\":\"BAN\",\"reason\":\"사유\",\"endsAt\":\"2030-03-01T00:00:00Z\"}",
            "{\"type\":\"WARNING\",\"reason\":\"사유\",\"endsAt\":\"2030-03-01T00:00:00Z\"}")) {
      send(post("/api/admin/sanctions"), body.replaceFirst("\\{", "{\"userId\":" + member + ","))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    send(get("/api/admin/reports").param("size", "101"), null).andExpect(status().isBadRequest());
    send(get("/api/admin/sanctions").param("page", "-1"), null).andExpect(status().isBadRequest());
    send(post("/api/admin/reports/999999/handle"), "{\"action\":\"RESOLVE\"}")
        .andExpect(status().isNotFound());
    send(post("/api/admin/sanctions/999999/release"), null).andExpect(status().isNotFound());
  }
}
