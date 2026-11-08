package com.pyeonit.push;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.pyeonit.config.ApiException;

@RestController
@RequestMapping("/api")
class PushController {
    private final PushService service;
    private final PushEndpointPolicy endpoints;
    private final PushConfiguration.Properties properties;
    PushController(PushService service, PushConfiguration.Properties properties, PushEndpointPolicy endpoints) {
        this.service = service;
        this.endpoints = endpoints;
        this.properties = properties;
    }

    @GetMapping("/push/vapid-public-key")
    Map<String, String> publicKey() {
        if (!properties.enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "푸시가 비활성화되어 있습니다");
        return Map.of("publicKey", properties.vapidPublicKey());
    }

    @PutMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void subscribe(Authentication auth, @Valid @RequestBody SubscriptionRequest request) {
        endpoints.validate(request.endpoint());
        PushValidation.keys(request.keys().p256dh(), request.keys().auth());
        service.subscribe(userId(auth), request);
    }

    @DeleteMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication auth, @RequestParam String endpoint) {
        endpoints.validate(endpoint);
        service.delete(userId(auth), endpoint);
    }

    @GetMapping("/me/notification-settings")
    Settings settings(Authentication auth) { return service.settings(userId(auth)); }

    @PutMapping("/me/notification-settings")
    Settings settings(Authentication auth, @Valid @RequestBody Settings settings) {
        return service.settings(userId(auth), settings);
    }

    private long userId(Authentication auth) {
        try { return Long.parseLong(auth.getName()); }
        catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SIGNUP_REQUIRED", "가입을 완료해 주세요");
        }
    }

    record Keys(@NotNull String p256dh, @NotNull String auth) {}
    record SubscriptionRequest(@NotNull String endpoint, @NotNull @Valid Keys keys, @Size(max = 500) String userAgent) {}
    record Settings(@NotNull Boolean newNoticeEnabled, @NotNull Boolean scheduleReminderEnabled) {}
}
