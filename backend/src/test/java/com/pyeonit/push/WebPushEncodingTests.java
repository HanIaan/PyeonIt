package com.pyeonit.push;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import nl.martijndwars.webpush.Notification;

class WebPushEncodingTests {
    @Test
    void generatesEncryptedVapidRequestWithoutAnyNetworkCallOrStoredKeys() throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var point = ((java.security.interfaces.ECPublicKey) pair.getPublic()).getW();
        byte[] publicBytes = new byte[65]; publicBytes[0] = 4;
        byte[] x = point.getAffineX().toByteArray(), y = point.getAffineY().toByteArray();
        System.arraycopy(x, Math.max(0, x.length - 32), publicBytes, 33 - Math.min(32, x.length), Math.min(32, x.length));
        System.arraycopy(y, Math.max(0, y.length - 32), publicBytes, 65 - Math.min(32, y.length), Math.min(32, y.length));
        var base64 = Base64.getUrlEncoder().withoutPadding();
        var properties = new PushConfiguration.Properties(base64.encodeToString(publicBytes),
                base64.encodeToString(((ECPrivateKey) pair.getPrivate()).getS().toByteArray()), "mailto:push@example.invalid");
        var encoder = new WebPushSender.RequestEncoder(properties);
        String subscriberKey = PushApiTests.publicKey();
        byte[] auth = new byte[16]; new SecureRandom().nextBytes(auth);
        byte[] payload = "{\"title\":\"test\",\"body\":\"test\",\"url\":\"/universities/1?academicYear=2027\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var encrypted = encoder.encode(new Notification("https://push.example.invalid/test", subscriberKey,
                base64.encodeToString(auth), payload));
        assertThat(encrypted.getHeaders()).containsEntry("Content-Encoding", "aes128gcm");
        assertThat(encrypted.getHeaders().get("Authorization")).startsWith("vapid ");
        assertThat(encrypted.getBody()).isNotEqualTo(payload);
        // Also verify all generated headers are accepted by the JDK transport.
        var request = HttpRequest.newBuilder(URI.create(encrypted.getUrl()));
        encrypted.getHeaders().forEach(request::header);
        request.POST(HttpRequest.BodyPublishers.ofByteArray(encrypted.getBody())).build();
    }

    @Test
    void rejectsCorrectLengthButInvalidCurvePoint() {
        byte[] invalid = new byte[65]; invalid[0] = 4;
        var base64 = Base64.getUrlEncoder().withoutPadding();
        assertThatThrownBy(() -> PushValidation.keys(base64.encodeToString(invalid), base64.encodeToString(new byte[16])))
                .isInstanceOf(com.pyeonit.config.ApiException.class);
    }
}
