package com.pyeonit.push;

import static org.assertj.core.api.Assertions.*;
import com.pyeonit.config.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PushEndpointPolicyTests {
    private final PushEndpointPolicy production = new PushEndpointPolicy(
            "fcm.googleapis.com,updates.push.services.mozilla.com,push.services.mozilla.com,notify.windows.com,push.apple.com");

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/send/1", "https://FCM.GOOGLEAPIS.COM:443/send/1",
            "https://updates.push.services.mozilla.com/wpush/v2/1", "https://push.services.mozilla.com/1",
            "https://region.notify.windows.com/1", "https://web.push.apple.com/1", "https://push.apple.com/1"})
    void allowsExactOrDotSeparatedSuffixAndNormalizesCase(String endpoint) {
        assertThatCode(() -> production.validate(endpoint)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://10.0.0.1/send", "https://172.16.0.1/send", "https://192.168.1.1/send",
            "https://127.0.0.1/send", "https://169.254.169.254/send", "https://8.8.8.8/send",
            "https://[::1]/send", "https://[fc00::1]/send", "https://[::ffff:127.0.0.1]/send",
            "https://2130706433/send", "https://127.1/send", "https://localhost/send", "https://a.localhost/send",
            "https://service.internal/send", "https://service.local/send", "https://intranet/send",
            "http://fcm.googleapis.com/send", "https://fcm.googleapis.com:8443/send", "https://fcm.googleapis.com:80/send",
            "https://fcm.googleapis.com:/send", "https://evil-fcm.googleapis.com.attacker.example/send",
            "https://evilfcm.googleapis.com/send", "https://fcm.googleapis.com.attacker.example/send",
            "https://user@fcm.googleapis.com/send", "https://fcm.googleapis.com@localhost/send",
            "https://fcm.googleapis.com./send", "https://fcm.googleapis.com/send#fragment",
            "https://push.example.invalid/send"})
    void rejectsNonPublicAndUntrustedDestinations(String endpoint) {
        assertThatThrownBy(() -> production.validate(endpoint)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(400);
            assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
        });
    }

    @Test void suffixConfigurationIsNormalizedAndNeverAllowsIpOrInternalNames() {
        var configured = new PushEndpointPolicy(" PUSH.EXAMPLE.INVALID , FCM.GOOGLEAPIS.COM ");
        configured.validate("https://PUSH.EXAMPLE.INVALID:443/test");
        configured.validate("https://region.push.example.invalid/test");
        assertThatThrownBy(() -> configured.validate("https://evilpush.example.invalid/test")).isInstanceOf(ApiException.class);
        for (String suffix : new String[]{"127.0.0.1", "[::1]", "localhost", "service.internal", "service.local"}) {
            assertThatThrownBy(() -> new PushEndpointPolicy(suffix)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PushEndpointPolicy("").validate("https://fcm.googleapis.com/send"))
                .isInstanceOf(ApiException.class);
    }

    @Test void transportRechecksBeforeKeyParsingOrAnyNetworkCall() {
        var sender = new WebPushSender(new PushConfiguration.Properties("test-placeholder", "test-placeholder",
                "mailto:push@example.invalid"), new ObjectMapper(), production);
        assertThatThrownBy(() -> sender.send(new PushSender.Subscription(1, "https://127.0.0.1/", "fake", "fake"),
                new PushSender.Payload("test", "test", "/"))).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
    }
}
