package com.pyeonit.push;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.sql.Timestamp;
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

@SpringBootTest(properties = {"pyeonit.push.allowed-endpoint-host-suffixes=push.example.invalid", "pyeonit.push.scheduling-enabled=false",
        "pyeonit.push.vapid-public-key=test-placeholder", "pyeonit.push.vapid-private-key=test-placeholder",
        "pyeonit.push.vapid-subject=mailto:push@example.invalid"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PushNotificationsTests.Fakes.class})
class PushNotificationsTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary FakeSender fakeSender() { return new FakeSender(); }
        // UTC date is Nov 7, while the Korean current date is Nov 8.
        @Bean @Primary TestClock pushTestClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-07T15:30:00Z");
        void set(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
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
    @Autowired PushService service;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired PushConfiguration.Properties properties;
    @Autowired PushEndpointPolicy endpoints;
    @Autowired TestClock clock;
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
        clock.set(Instant.parse("2026-11-07T15:30:00Z"));
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
        db.update("INSERT INTO push_subscriptions(user_id, endpoint, p256dh, auth, created_at) VALUES (?,?,'fake-public','fake-auth',?)", user, endpoint, Timestamp.from(Instant.parse("2026-11-07T15:00:00Z")));
    }
    void favorite(long user, long university) {
        db.update("INSERT INTO favorite_universities(user_id, university_id) VALUES (?,?)", user, university);
    }
    long document(long university, String status) {
        return db.queryForObject("""
            INSERT INTO admission_documents(university_id,academic_year,title,file_sha256,storage_key,status,reviewed_at)
            VALUES (?,2027,'2027 편입 모집요강',?, 'test-only', ?, '2026-11-07T15:30:00Z') RETURNING id
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

    @Test void onlyStrictlyLaterApprovalsAreEligibleAndSkippedNoticesAreNotLogged() {
        db.update("UPDATE admission_documents SET reviewed_at='2026-11-07T14:59:59Z' WHERE id=?", document);
        long equal = document(school, "APPROVED");
        db.update("UPDATE admission_documents SET reviewed_at='2026-11-07T15:00:00Z' WHERE id=?", equal);
        long missing = document(school, "APPROVED");
        db.update("UPDATE admission_documents SET reviewed_at=NULL WHERE id=?", missing);
        jobs.newNotices();
        assertThat(sender.deliveries).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isZero();
        document(school, "APPROVED");
        jobs.newNotices();
        assertThat(sender.deliveries).extracting(Delivery::endpoint)
                .containsExactlyInAnyOrder(endpoint(active), endpoint(suspended));
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(2);
    }

    @Test void eachSubscriptionUsesItsOwnCutoffEvenWithoutAPreviousPoll() {
        String later = "https://push.example.invalid/later";
        subscribe(active, later);
        db.update("UPDATE push_subscriptions SET created_at='2026-11-07T15:31:00Z' WHERE endpoint=?", later);
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).extracting(Delivery::endpoint)
                .containsExactlyInAnyOrder(endpoint(active), endpoint(suspended));
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(2);
    }

    @Test void sameOwnerReregistrationPreservesCreatedAtAndRefreshesKeysAndAgent() {
        Timestamp createdAt = subscriptionCreatedAt(endpoint(active));
        db.update("UPDATE push_subscriptions SET last_success_at=? WHERE endpoint=?", createdAt, endpoint(active));
        clock.set(clock.instant().plusSeconds(120));
        service.subscribe(active, request(endpoint(active), "updated"));
        assertThat(subscriptionCreatedAt(endpoint(active))).isEqualTo(createdAt);
        assertThat(db.queryForMap("SELECT p256dh, auth, user_agent, last_success_at FROM push_subscriptions WHERE endpoint=?",
                endpoint(active))).containsEntry("p256dh", "updated-public").containsEntry("auth", "updated-auth")
                .containsEntry("user_agent", "updated-agent").containsEntry("last_success_at", null);
    }

    @Test void approvalBetweenSubscriptionAndReregistrationIsDeliveredOnce() {
        db.update("DELETE FROM push_subscriptions");
        db.update("UPDATE admission_documents SET status='NEEDS_REVIEW', reviewed_at=NULL WHERE id=?", document);
        service.subscribe(active, request(endpoint(active), "initial"));
        Timestamp createdAt = subscriptionCreatedAt(endpoint(active));
        clock.set(clock.instant().plusSeconds(60));
        db.update("UPDATE admission_documents SET status='APPROVED', reviewed_at=? WHERE id=?",
                Timestamp.from(clock.instant()), document);
        clock.set(clock.instant().plusSeconds(60));
        service.subscribe(active, request(endpoint(active), "updated"));
        jobs.newNotices(); jobs.newNotices();
        assertThat(subscriptionCreatedAt(endpoint(active))).isEqualTo(createdAt);
        assertThat(sender.deliveries).extracting(Delivery::endpoint).containsExactly(endpoint(active));
        assertThat(db.queryForList("SELECT ref_key FROM notification_log WHERE user_id=?", Long.class, active))
                .containsExactly(document);
    }

    @Test void ownershipTransferResetsCutoffAndOnlyDeliversApprovalsAfterTransfer() {
        db.update("DELETE FROM push_subscriptions WHERE endpoint<>?", endpoint(suspended));
        Timestamp previousCreatedAt = subscriptionCreatedAt(endpoint(suspended));
        clock.set(clock.instant().plusSeconds(60));
        service.subscribe(active, request(endpoint(suspended), "transferred"));
        assertThat(subscriptionCreatedAt(endpoint(suspended))).isEqualTo(Timestamp.from(clock.instant()))
                .isAfter(previousCreatedAt);
        assertThat(db.queryForObject("SELECT user_id FROM push_subscriptions WHERE endpoint=?", Long.class, endpoint(suspended)))
                .isEqualTo(active);
        jobs.newNotices();
        assertThat(sender.deliveries).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isZero();
        clock.set(clock.instant().plusSeconds(60));
        long newer = document(school, "APPROVED");
        db.update("UPDATE admission_documents SET reviewed_at=? WHERE id=?", Timestamp.from(clock.instant()), newer);
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).extracting(Delivery::endpoint).containsExactly(endpoint(suspended));
        assertThat(db.queryForList("SELECT ref_key FROM notification_log WHERE user_id=?", Long.class, active))
                .containsExactly(newer);
    }

    @Test void newEndpointUsesCurrentClockTime() {
        clock.set(clock.instant().plusSeconds(120));
        String fresh = "https://push.example.invalid/fresh";
        service.subscribe(active, request(fresh, "initial"));
        assertThat(subscriptionCreatedAt(fresh)).isEqualTo(Timestamp.from(clock.instant()));
    }

    @Test void concurrentReregistrationsPreserveCreatedAtAndKeepKeysTogether() throws Exception {
        Timestamp createdAt = subscriptionCreatedAt(endpoint(active));
        clock.set(clock.instant().plusSeconds(120));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (String key : List.of("first", "second")) {
                futures.add(executor.submit(() -> {
                    start.await();
                    service.subscribe(active, request(endpoint(active), key));
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(subscriptionCreatedAt(endpoint(active))).isEqualTo(createdAt);
        var row = db.queryForMap("SELECT p256dh, auth, user_agent FROM push_subscriptions WHERE endpoint=?", endpoint(active));
        String key = ((String) row.get("p256dh")).replace("-public", "");
        assertThat(key).isIn("first", "second");
        assertThat(row).containsEntry("auth", key + "-auth").containsEntry("user_agent", key + "-agent");
    }

    private PushController.SubscriptionRequest request(String endpoint, String key) {
        return new PushController.SubscriptionRequest(endpoint,
                new PushController.Keys(key + "-public", key + "-auth"), key + "-agent");
    }

    private Timestamp subscriptionCreatedAt(String endpoint) {
        return db.queryForObject("SELECT created_at FROM push_subscriptions WHERE endpoint=?", Timestamp.class, endpoint);
    }

    @Test void deletingAndResubscribingDoesNotDeliverOlderApprovals() {
        service.delete(active, endpoint(active));
        service.subscribe(active, new PushController.SubscriptionRequest(endpoint(active),
                new PushController.Keys("fake-public", "fake-auth"), null));
        jobs.newNotices();
        assertThat(sender.deliveries).extracting(Delivery::endpoint).containsExactly(endpoint(suspended));
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log WHERE user_id=?", Integer.class, active)).isZero();
    }

    @Test void configurableIndividualLimitControlsBundling() {
        document(school, "APPROVED");
        var limited = new PushNotifications(db, transactions, sender, properties, clock,
                ZoneId.of("Asia/Seoul"), endpoints, 1);
        limited.newNotices(); limited.newNotices();
        assertThat(sender.deliveries).hasSize(2).allSatisfy(d -> assertThat(d.payload()).isEqualTo(
                new PushSender.Payload("관심학교 새 공고 2건", "관심학교의 새 공고가 2건 올라왔어요", "/notices")));
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(4);
    }

    @Test void threeNoticesAreSentIndividuallyAndAllAreLoggedOnce() {
        document(school, "APPROVED"); document(school, "APPROVED");
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).hasSize(6).allSatisfy(delivery ->
                assertThat(delivery.payload().url()).isEqualTo("/universities/" + school + "?academicYear=2027"));
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(6);
    }

    @Test void fourNoticesAreBundledPerMemberAndAllAreLoggedWithoutRedelivery() {
        document(school, "APPROVED"); document(school, "APPROVED"); document(school, "APPROVED");
        document(school, "NEEDS_REVIEW");
        document(otherSchool, "APPROVED"); // Must not inflate the target school's bundle count.
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).hasSize(3);
        assertThat(sender.deliveries.stream().filter(d -> !d.endpoint().equals(endpoint(outsider))))
                .hasSize(2).allSatisfy(d -> assertThat(d.payload()).isEqualTo(new PushSender.Payload(
                        "관심학교 새 공고 4건", "관심학교의 새 공고가 4건 올라왔어요", "/notices")));
        assertThat(sender.deliveries).extracting(Delivery::endpoint).doesNotContain(endpoint(off), endpoint(withdrawn));
        for (long user : new long[]{active, suspended}) {
            assertThat(db.queryForList("SELECT ref_key FROM notification_log WHERE user_id=?", Long.class, user))
                    .containsExactlyInAnyOrderElementsOf(db.queryForList(
                            "SELECT id FROM admission_documents WHERE university_id=? AND status='APPROVED'", Long.class, school));
        }
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(9);
    }

    @Test void bundleCountsOnlyNoticesAfterEachSubscriptionsCutoff() {
        document(school, "APPROVED"); document(school, "APPROVED");
        String later = "https://push.example.invalid/later";
        subscribe(active, later);
        db.update("UPDATE push_subscriptions SET created_at='2026-11-07T15:31:00Z' WHERE endpoint=?", later);
        long newer = document(school, "APPROVED");
        db.update("UPDATE admission_documents SET reviewed_at='2026-11-07T15:32:00Z' WHERE id=?", newer);
        jobs.newNotices(); jobs.newNotices();
        assertThat(sender.deliveries).hasSize(3);
        assertThat(sender.deliveries.stream().filter(d -> d.endpoint().equals(later)))
                .singleElement().satisfies(d -> assertThat(d.payload()).isEqualTo(new PushSender.Payload(
                        "관심학교 새 공고 1건", "관심학교의 새 공고가 1건 올라왔어요", "/notices")));
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isEqualTo(8);
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

    @Test void revalidatesPreviouslyStoredEndpointsBeforeCallingSender() {
        var unsafe = List.of("https://10.0.0.1/send", "https://localhost/send", "https://push.example.invalid:8443/send",
                "https://push.example.invalid.attacker.example/send", "https://user@push.example.invalid/send");
        unsafe.forEach(endpoint -> {
            subscribe(active, endpoint);
            sender.responses.put(endpoint, 410);
        });
        jobs.newNotices();
        assertThat(sender.deliveries).extracting(Delivery::endpoint)
                .containsExactlyInAnyOrder(endpoint(active), endpoint(suspended));
        assertThat(db.queryForList("SELECT endpoint FROM push_subscriptions WHERE last_success_at IS NULL AND user_id=?",
                String.class, active)).containsExactlyInAnyOrderElementsOf(unsafe);
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
