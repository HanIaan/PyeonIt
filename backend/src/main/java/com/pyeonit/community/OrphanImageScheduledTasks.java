package com.pyeonit.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "pyeonit.community.orphan-image-cleanup-enabled",
    havingValue = "true",
    matchIfMissing = true)
class OrphanImageScheduledTasks {
  private final OrphanImageCleanup cleanup;

  OrphanImageScheduledTasks(OrphanImageCleanup cleanup) {
    this.cleanup = cleanup;
  }

  @Scheduled(
      fixedDelayString = "${pyeonit.community.orphan-image-cleanup-interval:1h}",
      initialDelayString = "${pyeonit.community.orphan-image-cleanup-interval:1h}")
  void deleteOrphanImages() {
    cleanup.cleanup();
  }
}
