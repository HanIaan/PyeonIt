package com.pyeonit.admin.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "pyeonit.admin.community.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
class SanctionScheduledTasks {
  private final AdminCommunityService sanctions;
  private final JdbcSanctionBlocklist blocklist;

  SanctionScheduledTasks(AdminCommunityService sanctions, JdbcSanctionBlocklist blocklist) {
    this.sanctions = sanctions;
    this.blocklist = blocklist;
  }

  @Scheduled(fixedDelayString = "${pyeonit.admin.community.expiration-interval-ms:60000}")
  void expireSanctions() {
    sanctions.expireSanctions();
  }

  @Scheduled(cron = "${pyeonit.admin.community.blocklist-cleanup-cron:0 0 3 * * *}", zone = "UTC")
  void deleteExpiredBlocklist() {
    blocklist.deleteExpired();
  }
}
