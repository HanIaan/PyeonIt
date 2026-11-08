package com.pyeonit.push;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pyeonit.config.ApiException;
import com.pyeonit.push.PushController.Settings;
import com.pyeonit.push.PushController.SubscriptionRequest;

@Service
class PushService {
    private final JdbcTemplate db;
    private final int maxSubscriptionsPerUser;
    private final Clock clock;
    PushService(JdbcTemplate db, Clock clock, @Value("${pyeonit.push.max-subscriptions-per-user:10}") int maxSubscriptionsPerUser) {
        if (maxSubscriptionsPerUser < 1) throw new IllegalArgumentException("Push subscription limit must be positive");
        this.db = db;
        this.clock = clock;
        this.maxSubscriptionsPerUser = maxSubscriptionsPerUser;
    }

    @Transactional
    public void subscribe(long userId, SubscriptionRequest request) {
        requireMember(userId);
        db.update("""
            INSERT INTO push_subscriptions(user_id, endpoint, p256dh, auth, user_agent, created_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(endpoint) DO UPDATE SET user_id=excluded.user_id, p256dh=excluded.p256dh,
                auth=excluded.auth, user_agent=excluded.user_agent,
                created_at=CASE WHEN push_subscriptions.user_id=excluded.user_id
                    THEN push_subscriptions.created_at ELSE excluded.created_at END,
                last_success_at=NULL
            """, userId, request.endpoint(), request.keys().p256dh(), request.keys().auth(), request.userAgent(), Timestamp.from(clock.instant()));
        // The user lock serializes registrations. Keep the just-upserted endpoint even during a transfer.
        db.update("""
            DELETE FROM push_subscriptions WHERE user_id=? AND id IN (
                SELECT id FROM push_subscriptions WHERE user_id=? AND endpoint<>?
                ORDER BY created_at DESC, id DESC OFFSET ?
            )
            """, userId, userId, request.endpoint(), maxSubscriptionsPerUser - 1);
    }

    @Transactional
    public void delete(long userId, String endpoint) {
        requireMember(userId);
        db.update("DELETE FROM push_subscriptions WHERE user_id=? AND endpoint=?", userId, endpoint);
    }

    @Transactional
    public Settings settings(long userId) {
        requireMember(userId);
        return db.query("SELECT new_notice_enabled, schedule_reminder_enabled FROM notification_settings WHERE user_id=?",
                (rs, n) -> new Settings(rs.getBoolean(1), rs.getBoolean(2)), userId)
                .stream().findFirst().orElse(new Settings(true, true));
    }

    @Transactional
    public Settings settings(long userId, Settings settings) {
        requireMember(userId);
        db.update("""
            INSERT INTO notification_settings(user_id, new_notice_enabled, schedule_reminder_enabled) VALUES (?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET new_notice_enabled=excluded.new_notice_enabled,
                schedule_reminder_enabled=excluded.schedule_reminder_enabled
            """, userId, settings.newNoticeEnabled(), settings.scheduleReminderEnabled());
        return settings;
    }

    private void requireMember(long userId) {
        // Also serializes withdrawal against subscription registration and delivery.
        var states = db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE", String.class, userId);
        if (states.isEmpty() || "WITHDRAWN".equals(states.getFirst()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
    }
}
