package com.pyeonit.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "pyeonit.push.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class PushScheduledTasks {
    private final PushNotifications notifications;
    PushScheduledTasks(PushNotifications notifications) { this.notifications = notifications; }

    @Scheduled(fixedDelayString = "${pyeonit.push.notice-interval-ms:60000}")
    void notices() { notifications.newNotices(); }

    @Scheduled(cron = "${pyeonit.push.reminder-cron:0 0 9 * * *}", zone = "Asia/Seoul")
    void reminders() { notifications.scheduleReminders(); }
}
