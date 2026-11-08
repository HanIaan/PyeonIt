package com.pyeonit.search;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "pyeonit.search.rate-limit.ip-requests=3",
    "pyeonit.search.rate-limit.session-requests=2",
    "pyeonit.search.rate-limit.window=60s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SearchRateLimitTests {
  @Autowired MockMvc mvc;

  @Test
  void ipLimitsSpanSearchOperationsButNotPlainUniversityListsOrProfiles() throws Exception {
    for (String path : new String[]{"/api/posts/search", "/api/users/search", "/api/universities"}) {
      mvc.perform(get(path).param("q", "test").with(r -> { r.setRemoteAddr("192.0.2.1"); return r; }))
          .andExpect(status().isOk());
    }
    mvc.perform(get("/api/posts/search").param("q", "test")
        .header("X-Forwarded-For", "192.0.2.99")
        .with(r -> { r.setRemoteAddr("192.0.2.1"); return r; }))
        .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.fieldErrors").isArray());
    mvc.perform(get("/api/universities").with(r -> { r.setRemoteAddr("192.0.2.1"); return r; }))
        .andExpect(status().isOk());
    mvc.perform(get("/api/users/9223372036854775807").with(r -> { r.setRemoteAddr("192.0.2.1"); return r; }))
        .andExpect(status().isNotFound());
  }

  @Test
  void sessionLimitPersistsAcrossIpChangesAndAnonymousGetsNoSession() throws Exception {
    MockHttpSession session = new MockHttpSession();
    for (int i = 0; i < 2; i++) {
      String ip = "192.0.2." + (i + 10);
      mvc.perform(get("/api/users/search").param("q", "test").session(session)
          .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk());
    }
    var limited = mvc.perform(get("/api/universities").param("q", "test").session(session)
        .with(r -> { r.setRemoteAddr("192.0.2.12"); return r; }))
        .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andReturn();
    assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
    var result = mvc.perform(get("/api/users/search").param("q", "test")
        .with(r -> { r.setRemoteAddr("192.0.2.20"); return r; })).andExpect(status().isOk()).andReturn();
    assertThat(result.getRequest().getSession(false)).isNull();
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
  }

  @Test
  void injectedTimeResetsLimitsAndRemovesExpiredCounters() {
    var clock = new MutableClock();
    var limiter = new SearchRateLimiter(clock, Duration.ofSeconds(60), 2, 1);
    assertThat(limiter.acquire("one", "session")).isZero();
    assertThat(limiter.acquire("two", "session")).isEqualTo(60);
    assertThat(limiter.counterCount()).isEqualTo(2); // rejected requests allocate nothing
    assertThat(limiter.acquire("one", null)).isZero();
    clock.now = clock.now.plusSeconds(1);
    assertThat(limiter.acquire("one", null)).isEqualTo(59);
    clock.now = clock.now.plusSeconds(59);
    assertThat(limiter.acquire("three", null)).isZero();
    assertThat(limiter.counterCount()).isEqualTo(1);
  }

  @Test
  void cleanupRunsAtMostOncePerSecondAndExpiredKeysStillResetImmediately() {
    var clock = new MutableClock();
    var limiter = new SearchRateLimiter(clock, Duration.ofMillis(100), 1, 1);
    assertThat(limiter.acquire("expired", null)).isZero();
    clock.now = clock.now.plusMillis(100);
    assertThat(limiter.acquire("current", "session")).isZero();
    assertThat(limiter.counterCount()).isEqualTo(3); // no full cleanup inside the first second
    clock.now = clock.now.plusMillis(100);
    assertThat(limiter.acquire("current", "session")).isZero(); // expired keys reset individually
    assertThat(limiter.counterCount()).isEqualTo(3);
    clock.now = clock.now.plusMillis(800);
    assertThat(limiter.acquire("fresh", null)).isZero();
    assertThat(limiter.counterCount()).isEqualTo(1); // next cleanup removes all idle keys
  }

  @Test
  void concurrentRequestsCannotExceedLimit() throws Exception {
    var limiter = new SearchRateLimiter(Clock.systemUTC(), Duration.ofMinutes(1), 7, 3);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = java.util.stream.IntStream.range(0, 100)
          .<java.util.concurrent.Callable<Long>>mapToObj(i -> () -> limiter.acquire("same", null)).toList();
      long accepted = 0;
      for (var result : executor.invokeAll(tasks)) if (result.get() == 0) accepted++;
      assertThat(accepted).isEqualTo(7);
    }
  }
}
