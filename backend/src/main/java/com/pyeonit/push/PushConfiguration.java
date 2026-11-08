package com.pyeonit.push;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushConfiguration.Properties.class)
class PushConfiguration {
    @Bean
    ZoneId pushZone() { return ZoneId.of("Asia/Seoul"); }

    @ConfigurationProperties("pyeonit.push")
    record Properties(String vapidPublicKey, String vapidPrivateKey, String vapidSubject) {
        boolean enabled() {
            return present(vapidPublicKey) && present(vapidPrivateKey) && present(vapidSubject);
        }
        private boolean present(String value) { return value != null && !value.isBlank(); }
    }
}
