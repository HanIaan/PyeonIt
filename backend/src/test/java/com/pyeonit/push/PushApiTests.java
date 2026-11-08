package com.pyeonit.push;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import com.pyeonit.TestcontainersConfiguration;

@SpringBootTest(properties = {"pyeonit.push.scheduling-enabled=false", "pyeonit.push.vapid-public-key=",
        "pyeonit.push.vapid-private-key=", "pyeonit.push.vapid-subject="})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PushApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired PushNotifications notifications;
    long first;
    long second;
    String body;
    static final String ENDPOINT = "https://push.example.invalid/subscription";

    @BeforeEach
    void setup() throws Exception {
        db.execute("TRUNCATE users CASCADE");
        first = db.queryForObject("INSERT INTO users(nickname) VALUES ('pushone') RETURNING id", Long.class);
        second = db.queryForObject("INSERT INTO users(nickname, status) VALUES ('pushtwo', 'SUSPENDED') RETURNING id", Long.class);
        body = "{\"endpoint\":\"" + ENDPOINT + "\",\"keys\":{\"p256dh\":\"" + publicKey()
                + "\",\"auth\":\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]) + "\"}}";
    }

    static String publicKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var point = ((ECPublicKey) generator.generateKeyPair().getPublic()).getW();
        byte[] encoded = new byte[65];
        encoded[0] = 4;
        byte[] x = point.getAffineX().toByteArray(), y = point.getAffineY().toByteArray();
        System.arraycopy(x, Math.max(0, x.length - 32), encoded, 33 - Math.min(32, x.length), Math.min(32, x.length));
        System.arraycopy(y, Math.max(0, y.length - 32), encoded, 65 - Math.min(32, y.length), Math.min(32, y.length));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
    }

    @Test
    void endpointUpsertTransferAndOwnerOnlyIdempotentDeletion() throws Exception {
        for (long owner : new long[]{first, first, second}) {
            mvc.perform(put("/api/me/push-subscriptions").with(user("" + owner)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM push_subscriptions", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT user_id FROM push_subscriptions", Long.class)).isEqualTo(second);
        mvc.perform(delete("/api/me/push-subscriptions").param("endpoint", ENDPOINT).with(user("" + first)).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT count(*) FROM push_subscriptions", Integer.class)).isEqualTo(1);
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/api/me/push-subscriptions").param("endpoint", ENDPOINT).with(user("" + second)).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM push_subscriptions", Integer.class)).isZero();
    }

    @Test
    void settingsDefaultAndFullUpdateIncludingSuspendedMember() throws Exception {
        mvc.perform(get("/api/me/notification-settings").with(user("" + second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newNoticeEnabled").value(true))
                .andExpect(jsonPath("$.scheduleReminderEnabled").value(true));
        mvc.perform(put("/api/me/notification-settings").with(user("" + second)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"newNoticeEnabled\":false,\"scheduleReminderEnabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newNoticeEnabled").value(false));
        mvc.perform(get("/api/me/notification-settings").with(user("" + second)))
                .andExpect(jsonPath("$.newNoticeEnabled").value(false));
        mvc.perform(get("/api/me/notification-settings").with(user("" + first)))
                .andExpect(jsonPath("$.newNoticeEnabled").value(true));
        mvc.perform(put("/api/me/notification-settings").with(user("" + first)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"newNoticeEnabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidEndpointsKeysAndAgentAreRejected() throws Exception {
        for (String invalid : List.of(body.replace("https:", "http:"), body.replace(ENDPOINT, "https://user@host.invalid/path"),
                body.replace(ENDPOINT, "https://host.invalid/#fragment"), body.replace(ENDPOINT, ENDPOINT + "x".repeat(2048)),
                body.replaceAll("p256dh\":\"[^\"]+", "p256dh\":\"invalid"),
                body.replaceAll("auth\":\"[^\"]+", "auth\":\"invalid"),
                body.substring(0, body.length()-1) + ",\"userAgent\":\"" + "a".repeat(501) + "\"}")) {
            mvc.perform(put("/api/me/push-subscriptions").with(user("" + first)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(delete("/api/me/push-subscriptions").with(user("" + first)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousPendingAndCsrfContracts() throws Exception {
        mvc.perform(get("/api/me/notification-settings")).andExpect(status().isUnauthorized());
        var pending = UsernamePasswordAuthenticationToken.authenticated("pending:kakao:123456", null, List.of());
        mvc.perform(get("/api/me/notification-settings").with(authentication(pending)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
        mvc.perform(put("/api/me/push-subscriptions").with(authentication(pending)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
        mvc.perform(delete("/api/me/push-subscriptions").with(authentication(pending)).with(csrf()).param("endpoint", ENDPOINT))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
        mvc.perform(put("/api/me/notification-settings").with(authentication(pending)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"newNoticeEnabled\":true,\"scheduleReminderEnabled\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
        mvc.perform(put("/api/me/push-subscriptions").with(user("" + first)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void missingKeysDisableJobsAndPublicKeyButAllowSubscriptionsAndWithdrawalCleanup() throws Exception {
        mvc.perform(get("/api/push/vapid-public-key")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(put("/api/me/push-subscriptions").with(user("" + first)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        notifications.newNotices();
        notifications.scheduleReminders();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_log", Integer.class)).isZero();
        db.update("UPDATE users SET status='WITHDRAWN', nickname=NULL WHERE id=?", first);
        assertThat(db.queryForObject("SELECT count(*) FROM push_subscriptions", Integer.class)).isZero();
        mvc.perform(put("/api/me/push-subscriptions").with(user("" + first)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
