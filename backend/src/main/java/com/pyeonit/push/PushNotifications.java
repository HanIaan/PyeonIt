package com.pyeonit.push;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
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
    private final PushEndpointPolicy endpoints;
    private final PushConfiguration.Properties properties;
    private final Clock clock;
    private final ZoneId zone;
    private final int maxIndividualNotices;

    PushNotifications(JdbcTemplate db, PlatformTransactionManager manager, PushSender sender,
            PushConfiguration.Properties properties, Clock clock, ZoneId pushZone, PushEndpointPolicy endpoints,
            @Value("${pyeonit.push.max-individual-notices:3}") int maxIndividualNotices) {
        if (maxIndividualNotices < 1) throw new IllegalArgumentException("Push notice limit must be positive");
        this.maxIndividualNotices = maxIndividualNotices;
        this.db = db;
        this.transaction = new TransactionTemplate(manager);
        this.sender = sender;
        this.endpoints = endpoints;
        this.properties = properties;
        this.clock = clock;
        this.zone = pushZone;
    }

    public void newNotices() {
        if (!properties.enabled()) return;
        var users = db.queryForList("""
            SELECT DISTINCT p.user_id FROM push_subscriptions p JOIN users u ON u.id=p.user_id
            LEFT JOIN notification_settings ns ON ns.user_id=u.id
            WHERE u.status<>'WITHDRAWN' AND COALESCE(ns.new_notice_enabled, TRUE)
            ORDER BY p.user_id
            """, Long.class);
        for (long userId : users) {
            try {
                transaction.executeWithoutResult(status -> deliverNewNotices(userId));
            } catch (Exception e) {
                log.warn("Push job failed: userId={}, kind=NEW_NOTICE, error={}",
                        userId, e.getClass().getSimpleName());
            }
        }
    }

    private void deliverNewNotices(long userId) {
        var states = db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE", String.class, userId);
        if (states.isEmpty() || "WITHDRAWN".equals(states.getFirst())) return;
        // Lock subscriptions before checking cutoffs so endpoint ownership cannot change during delivery.
        var subscriptions = db.query("""
            SELECT id, endpoint, p256dh, auth, created_at FROM push_subscriptions
            WHERE user_id=? ORDER BY id FOR UPDATE
            """, (rs, n) -> new TimedSubscription(new Subscription(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), rs.getTimestamp(5).toInstant()), userId);
        if (subscriptions.isEmpty()) return;
        var candidates = db.query("""
            SELECT d.id, d.university_id, d.academic_year, u.name, d.title, d.reviewed_at
            FROM admission_documents d JOIN universities u ON u.id=d.university_id
            JOIN favorite_universities f ON f.university_id=d.university_id AND f.user_id=?
            LEFT JOIN notification_settings ns ON ns.user_id=f.user_id
            WHERE d.status='APPROVED' AND d.reviewed_at IS NOT NULL
                AND COALESCE(ns.new_notice_enabled, TRUE)
                AND EXISTS(SELECT 1 FROM push_subscriptions p WHERE p.user_id=f.user_id
                    AND d.reviewed_at>p.created_at)
                AND NOT EXISTS(SELECT 1 FROM notification_log l WHERE l.user_id=f.user_id
                    AND l.kind='NEW_NOTICE' AND l.ref_key=d.id)
            ORDER BY d.reviewed_at, d.id
            """, (rs, n) -> new Notice(new Target(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                rs.getString(4) + " 새 공고", rs.getString(5)), rs.getTimestamp(6).toInstant()), userId);
        var claimed = new ArrayList<Notice>();
        for (var notice : candidates) {
            if (db.update("""
                INSERT INTO notification_log(user_id, kind, ref_key, sent_at) VALUES (?, 'NEW_NOTICE', ?, ?)
                ON CONFLICT(user_id, kind, ref_key) DO NOTHING
                """, userId, notice.target().id(), Timestamp.from(clock.instant())) > 0) claimed.add(notice);
        }
        boolean bundle = claimed.size() > maxIndividualNotices;
        for (var timed : subscriptions) {
            var notices = claimed.stream().filter(n -> n.reviewedAt().isAfter(timed.createdAt())).toList();
            if (notices.isEmpty()) continue;
            if (bundle) {
                int count = notices.size();
                send(userId, timed.subscription(), new Payload("관심학교 새 공고 " + count + "건",
                        "관심학교의 새 공고가 " + count + "건 올라왔어요", "/notices"));
            } else {
                for (var notice : notices) send(userId, timed.subscription(), payload(notice.target()));
            }
        }
    }

    private record TimedSubscription(Subscription subscription, Instant createdAt) {}
    private record Notice(Target target, Instant reviewedAt) {}

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
                endpoints.validate(subscription.endpoint());
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

    private Payload payload(Target target) {
        return new Payload(target.title(), target.body(),
                "/universities/" + target.universityId() + "?academicYear=" + target.academicYear());
    }

    private void send(long userId, Subscription subscription, Payload payload) {
        try {
            endpoints.validate(subscription.endpoint());
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

    private record Target(long id, long universityId, int academicYear, String title, String body) {}
}
