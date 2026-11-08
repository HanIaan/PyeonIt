package com.pyeonit.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class WebPushSender implements PushSender {
    private final PushConfiguration.Properties properties;
    private final ObjectMapper json;
    private final PushEndpointPolicy endpoints;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    WebPushSender(PushConfiguration.Properties properties, ObjectMapper json, PushEndpointPolicy endpoints) {
        this.properties = properties;
        this.json = json;
        this.endpoints = endpoints;
        // No key parsing on startup: environments without keys remain fully usable.
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public int send(Subscription subscription, Payload payload) throws Exception {
        if (!properties.enabled()) return 0;
        endpoints.validate(subscription.endpoint());
        var service = new RequestEncoder(properties);
        var notification = new Notification(subscription.endpoint(), subscription.p256dh(), subscription.auth(),
                json.writeValueAsBytes(payload));
        var encrypted = service.encode(notification);
        var request = HttpRequest.newBuilder(URI.create(encrypted.getUrl())).timeout(Duration.ofSeconds(10));
        encrypted.getHeaders().forEach(request::header);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(encrypted.getBody())).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // Use only the library's VAPID/encryption support; HTTP is handled by the JDK with bounded timeouts.
    static class RequestEncoder extends AbstractPushService<RequestEncoder> {
        RequestEncoder(PushConfiguration.Properties properties) throws GeneralSecurityException {
            super(properties.vapidPublicKey(), properties.vapidPrivateKey(), properties.vapidSubject());
        }
        nl.martijndwars.webpush.HttpRequest encode(Notification notification) throws Exception {
            return prepareRequest(notification, Encoding.AES128GCM);
        }
    }
}
