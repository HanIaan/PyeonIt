package com.pyeonit.auth;

import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.util.StringUtils;

/**
 * pyeonit.oauth2.* (spring.security.oauth2.client.* 와 같은 모양) 에서 client-id 가 있는 제공자만 등록한다.
 * spring.security 아래에 두면 빈 client-id 로 기동이 실패하므로 따로 둔다. 하나도 없으면 빈이 없고 SecurityConfig 가 oauth2Login 을 끈다.
 */
@Configuration(proxyBeanMethods = false)
class OAuth2ClientConfig {

	static Map<String, ClientRegistration> registrations(Environment env) {
		OAuth2ClientProperties props = Binder.get(env).bind("pyeonit.oauth2", OAuth2ClientProperties.class)
				.orElseGet(OAuth2ClientProperties::new);
		props.getRegistration().values().removeIf(r -> !StringUtils.hasText(r.getClientId()));
		return new OAuth2ClientPropertiesMapper(props).asClientRegistrations();
	}

	static class ProvidersConfigured implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return !registrations(context.getEnvironment()).isEmpty();
		}

	}

	@Bean
	@Conditional(ProvidersConfigured.class)
	ClientRegistrationRepository clientRegistrationRepository(Environment env) {
		return new InMemoryClientRegistrationRepository(registrations(env).values().stream().toList());
	}

}
