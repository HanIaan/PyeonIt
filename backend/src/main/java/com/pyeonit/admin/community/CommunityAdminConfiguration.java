package com.pyeonit.admin.community;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CommunityAdminConfiguration {
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock sanctionClock() {
    return Clock.systemUTC();
  }
}
