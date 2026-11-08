package com.pyeonit.search;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Fixed windows anchored at first request; atomic IP/session accounting and idle expiry. */
public final class SearchRateLimiter {
  private record Window(long expiresAt, int count) {}
  private final Map<String, Window> counters = new HashMap<>();
  private final Clock clock;
  private final long windowMillis;
  private final int ipLimit;
  private final int sessionLimit;

  public SearchRateLimiter(Clock clock, Duration window, int ipLimit, int sessionLimit) {
    if (window.toMillis() < 1 || ipLimit < 1 || sessionLimit < 1)
      throw new IllegalArgumentException("Search rate limits must be positive");
    this.clock = clock;
    this.windowMillis = window.toMillis();
    this.ipLimit = ipLimit;
    this.sessionLimit = sessionLimit;
  }

  /** Returns retry seconds, or zero when accepted. Anonymous requests create no session. */
  public synchronized long acquire(String ip, String session) {
    long now = clock.millis();
    counters.values().removeIf(w -> w.expiresAt <= now);
    String ipKey = "ip:" + ip;
    String sessionKey = session == null ? null : "session:" + session;
    long retry = blocked(ipKey, ipLimit, now);
    if (sessionKey != null) retry = Math.max(retry, blocked(sessionKey, sessionLimit, now));
    if (retry > 0) return retry;
    increment(ipKey, now);
    if (sessionKey != null) increment(sessionKey, now);
    return 0;
  }

  private long blocked(String key, int limit, long now) {
    Window w = counters.get(key);
    return w != null && w.count >= limit ? Math.max(1, (w.expiresAt - now + 999) / 1000) : 0;
  }

  private void increment(String key, long now) {
    Window old = counters.get(key);
    counters.put(key, old == null ? new Window(now + windowMillis, 1)
        : new Window(old.expiresAt, old.count + 1));
  }

  synchronized int counterCount() { return counters.size(); }
}
