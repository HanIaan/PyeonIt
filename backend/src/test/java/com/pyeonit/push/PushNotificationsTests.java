package com.pyeonit.push;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.pyeonit.TestcontainersConfiguration;

@SpringBootTest(properties = {"pyeonit.push.scheduling-enabled=false",
        "pyeonit.push.vapid-public-key=test-placeholder", "pyeonit.push.vapid-private-key=test-placeholder",
        "pyeonit.push.vapid-subject=mailto:push@example.invalid"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PushNotificationsTests.Fakes.class})
class PushNotificationsTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary FakeSender fakeSender() { return new FakeSender(); }
        // UTC date is Nov 7, while the Korean current date is Nov 8.
        @Bean @Primary Clock pushTestClock() { return Clock.fixed(Instant.parse("2026-11-07T15:30:00Z"), ZoneOffset.UTC); }
    }
    static class FakeSender implements PushSender {
        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        final Map<String, Integer> responses = new HashMap<>();
        final Set<String> exceptions = new HashSet<>();
        @Override public int send(Subscription subscription, Payload payload) {
            deliveries.add(new Delivery(subscription.endpoint(), payload));
            if (exceptions.contains(subscription.endpoint())) throw new IllegalStateException("fake failure");
            return responses.getOrDefault(subscription.endpoint(), 201);
        }
    }
    record Delivery(String endpoint, PushSender.Payload payload) {}
    @Autowired JdbcTemplate db;
    @Autowired PushNotifications jobs;
    @Autowired FakeSender sender;
    @Autowired MockMvc mvc;
    long school;
    long otherSchool;
    long document;
    long active;
    long suspended;
    long off;
    long outsider;
    long withdrawn;

    @BeforeEach void setup() {
        db.execute("TRUNCATE users, universities CASCADE");
        sender.deliveries.clear(); sender.responses.clear(); sender.exceptions.clear();
        school = school("대상대학교"); otherSchool = school("다른대학교");
        active = member("active", "ACTIVE"); suspended = member("suspended", "SUSPENDED");
        off = member("disabled", "ACTIVE"); outsider = member("outsider", "ACTIVE");
        withdrawn = member("withdrawn", "ACTIVE");
        for (long id : new long[]{active, suspended, off, withdrawn}) favorite(id, school);
        favorite(outsider, otherSchool);
        db.update("INSERT INTO notification_settings VALUES (?, false, false)", off);
        db.update("UPDATE users SET status='WITHDRAWN', nickname=NULL WHERE id=?", withdrawn);
        document = document(school, "APPROVED");
    }
    long school(String name) {
        return db.queryForObject("INSERT INTO universities(name, region) VALUES (?, 'SEOUL') RETURNING id", Long.class, name);
    }
    long member(String name, String status) {
        long id = db.queryForObject("INSERT INTO users(nickname,status) VALUES (?,?) RETURNING id", Long.class, name, status);
        subscribe(id, endpoint(id));
        return id;
    }
    String endpoint(long user) { return "https://push.example.invalid/" + user; }
    void subscribe(long user, String endpoint) {
        db.update("INSERT INTO push_subscriptions(user_id, endpoint, p256dh, auth) VALUES (?,?,'fake-public','fake-auth')", user, endpoint);
    }
    void favorite(long user, long university) {
        db.update("INSERT INTO favorite_universities(user_id, university_id) VALUES (?,?)", user, university);
    }
    long document(long university, String status) {
        return db.queryForObject("""
            INSERT INTO admission_documents(university_id,academic_year,title,file_sha256,storage_key,status)
            VALUES (?,2027,'2027 편입 모집요강',?, 'test-only', ?) RETURNING id
            """, Long.class, university, UUID.randomUUID().toString().replace("-", "").repeat(2), status);
    }
    long schedule(long doc, String start, boolean allDay) {
        return db.queryForObject("""
            INSERT INTO schedules(document_id,type,raw_label,starts_at,all_day)
            VALUES (?,'APPLICATION','원서 접수',?::timestamptz,?) RETURNING id
            """, Long.class, doc, start, allDay);
    }

    @Test void selectsFavoritesHonorsSettingsAndSuspensionAndPollsApprovalOnce() {
        long pending = document(school, "NEEDS_REVIEW");
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).extracting(Delivery::endpoint).containsExactlyInAnyOrder(endpoint(active), endpoint(suspended));
        assertThat(sender.deliveries.getFirst().payload()).isEqualTo(new PushSender.Payload("대상대학교 새 공고", "2027 편입 모집요강",
                "/universities/" + school + "?academicYear=2027"));
        db.update("UPDATE admission_documents SET status='APPROVED' WHERE id=?", pending);
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).hasSize(4);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id=?", Integer.class, withdrawn)).isZero();
    }

    @Test void dMinusOneUsesKoreanDateIncludesAllDayAndExcludesBothBoundaries() {
        schedule(document, "2026-11-08T14:59:59Z", false); // Nov 8 KST: today
        schedule(document, "2026-11-08T15:00:00Z", true);  // Nov 9 KST: tomorrow at midnight
        schedule(document, "2026-11-09T14:59:59Z", false); // Nov 9 KST: last second
        schedule(document, "2026-11-09T15:00:00Z", true);  // Nov 10 KST
        schedule(document(school, "NEEDS_REVIEW"), "2026-11-08T15:00:00Z", true);
        schedule(document(otherSchool, "APPROVED"), "2026-11-08T15:00:00Z", true);
        jobs.scheduleReminders(); jobs.scheduleReminders();
        assertThat(sender.deliveries).hasSize(5); // two target schedules x two members + other school's favorite
        assertThat(sender.deliveries.stream().filter(d -> !d.endpoint().equals(endpoint(outsider))))
                .hasSize(4).allSatisfy(d -> {
                    assertThat(d.payload()).isEqualTo(new PushSender.Payload("대상대학교 일정 D-1", "원서 접수 · 2026-11-09 예정",
                            "/universities/" + school + "?academicYear=2027"));
                });
        assertThat(sender.deliveries).extracting(Delivery::endpoint).doesNotContain(endpoint(off), endpoint(withdrawn));
    }

    @Test void removesExpiredEndpointsIsolatesTemporaryFailuresAndNeverRetriesSameTarget() {
        sender.responses.put(endpoint(active), 404);
        sender.responses.put(endpoint(suspended), 410);
        String temporary = "https://push.example.invalid/temporary";
        String failing = "https://push.example.invalid/failing";
        String good = "https://push.example.invalid/good";
        subscribe(active, temporary); subscribe(active, failing); subscribe(active, good);
        sender.responses.put(temporary, 503); sender.exceptions.add(failing);
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).hasSize(5);
        assertThat(db.queryForList("SELECT endpoint FROM push_subscriptions WHERE user_id IN (?,?)", String.class, active, suspended))
                .containsExactlyInAnyOrder(temporary, failing, good);
        assertThat(db.queryForObject("SELECT last_success_at IS NOT NULL FROM push_subscriptions WHERE endpoint=?", Boolean.class, good)).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(2);
        document(school, "APPROVED");
        jobs.newNotices();
        assertThat(sender.deliveries).hasSize(8);
    }

    @Test void subscriptionTransferDoesNotSendOldOwnersNotification() {
        // Transfer to a user who has no favorite for the target school.
        db.update("UPDATE push_subscriptions SET user_id=? WHERE user_id=?", outsider, active);
        jobs.newNotices();
        assertThat(sender.deliveries).extracting(Delivery::endpoint).containsExactly(endpoint(suspended));
    }

    @Test void concurrentPollersClaimEachUserAndTargetOnlyOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { await(start); jobs.newNotices(); });
            var second = executor.submit(() -> { await(start); jobs.newNotices(); });
            start.countDown(); first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(sender.deliveries).hasSize(2);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(2);
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    @Test void publicKeyIsAnonymousAndOnlyPublicPartIsReturned() throws Exception {
        mvc.perform(get("/api/push/vapid-public-key")).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value("test-placeholder"))
                .andExpect(jsonPath("$.privateKey").doesNotExist()).andExpect(jsonPath("$.vapidSubject").doesNotExist());
    }
}
