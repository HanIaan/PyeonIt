package com.pyeonit.push;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.pyeonit.push.PushSender.Payload;
import com.pyeonit.push.PushSender.Subscription;

@Service
class PushNotifications {
    private static final Logger log = LoggerFactory.getLogger(PushNotifications.class);
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final PushSender sender;
    private final PushConfiguration.Properties properties;
    private final Clock clock;
    private final ZoneId zone;

    PushNotifications(JdbcTemplate db, PlatformTransactionManager manager, PushSender sender,
            PushConfiguration.Properties properties, Clock clock, ZoneId pushZone) {
        this.db = db;
        this.transaction = new TransactionTemplate(manager);
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
        this.zone = pushZone;
    }

    public void newNotices() {
        if (!properties.enabled()) return;
        dispatch("NEW_NOTICE", "new_notice_enabled", db.query("""
            SELECT d.id, d.university_id, d.academic_year, u.name, d.title
            FROM admission_documents d JOIN universities u ON u.id=d.university_id WHERE d.status='APPROVED'
            """, (rs, n) -> new Target(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                rs.getString(4) + " 새 공고", rs.getString(5))));
    }

    public void scheduleReminders() {
        if (!properties.enabled()) return;
        LocalDate tomorrow = LocalDate.now(clock.withZone(zone)).plusDays(1);
        dispatch("SCHEDULE_REMINDER", "schedule_reminder_enabled", db.query("""
            SELECT s.id, d.university_id, d.academic_year, u.name, s.raw_label
            FROM schedules s JOIN admission_documents d ON d.id=s.document_id
            JOIN universities u ON u.id=d.university_id
            WHERE d.status='APPROVED' AND s.starts_at>=? AND s.starts_at<?
            """, (rs, n) -> new Target(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                rs.getString(4) + " 일정 D-1", rs.getString(5) + " · " + tomorrow + " 예정"),
            Timestamp.from(tomorrow.atStartOfDay(zone).toInstant()),
            Timestamp.from(tomorrow.plusDays(1).atStartOfDay(zone).toInstant())));
    }

    private void dispatch(String kind, String setting, List<Target> targets) {
        for (Target target : targets) {
            // setting is a fixed internal column name, never request input.
            var users = db.queryForList("""
                SELECT f.user_id FROM favorite_universities f JOIN users u ON u.id=f.user_id
                LEFT JOIN notification_settings ns ON ns.user_id=u.id
                WHERE f.university_id=? AND u.status<>'WITHDRAWN' AND COALESCE(ns.%s, TRUE)
                AND EXISTS(SELECT 1 FROM push_subscriptions p WHERE p.user_id=u.id)
                AND NOT EXISTS(SELECT 1 FROM notification_log l WHERE l.user_id=u.id AND l.kind=? AND l.ref_key=?)
                ORDER BY f.user_id
                """.formatted(setting), Long.class, target.universityId(), kind, target.id());
            for (long userId : users) {
                try {
                    transaction.executeWithoutResult(status -> deliver(userId, kind, setting, target));
                } catch (Exception e) {
                    // Never log endpoints, key material, payloads or service exception messages.
                    log.warn("Push job failed: userId={}, kind={}, ref={}, error={}",
                            userId, kind, target.id(), e.getClass().getSimpleName());
                }
            }
        }
    }

    private void deliver(long userId, String kind, String setting, Target target) {
        var states = db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE", String.class, userId);
        if (states.isEmpty() || "WITHDRAWN".equals(states.getFirst())) return;
        Boolean eligible = db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM favorite_universities f
                LEFT JOIN notification_settings ns ON ns.user_id=f.user_id
                WHERE f.user_id=? AND f.university_id=? AND COALESCE(ns.%s, TRUE))
            """.formatted(setting), Boolean.class, userId, target.universityId());
        if (!Boolean.TRUE.equals(eligible)) return;
        int claimed = db.update("""
            INSERT INTO notification_log(user_id, kind, ref_key, sent_at) VALUES (?, ?, ?, ?)
            ON CONFLICT(user_id, kind, ref_key) DO NOTHING
            """, userId, kind, target.id(), Timestamp.from(clock.instant()));
        if (claimed == 0) return;
        // Ownership cannot move between the final check and transmission.
        var subscriptions = db.query("""
            SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id=? ORDER BY id FOR UPDATE
            """, (rs, n) -> new Subscription(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), userId);
        var payload = new Payload(target.title(), target.body(),
                "/universities/" + target.universityId() + "?academicYear=" + target.academicYear());
        for (var subscription : subscriptions) {
            try {
                int response = sender.send(subscription, payload);
                if (response == 404 || response == 410) {
                    db.update("DELETE FROM push_subscriptions WHERE id=? AND user_id=?", subscription.id(), userId);
                } else if (response >= 200 && response < 300) {
                    db.update("UPDATE push_subscriptions SET last_success_at=? WHERE id=?",
                            Timestamp.from(clock.instant()), subscription.id());
                } else {
                    log.warn("Push rejected: subscriptionId={}, status={}", subscription.id(), response);
                }
            } catch (Exception e) {
                log.warn("Push delivery failed: subscriptionId={}, error={}",
                        subscription.id(), e.getClass().getSimpleName());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    private record Target(long id, long universityId, int academicYear, String title, String body) {}
}
