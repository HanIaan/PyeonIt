package com.pyeonit.push;

public interface PushSender {
    int send(Subscription subscription, Payload payload) throws Exception;

    record Subscription(long id, String endpoint, String p256dh, String auth) {}
    record Payload(String title, String body, String url) {}
}
