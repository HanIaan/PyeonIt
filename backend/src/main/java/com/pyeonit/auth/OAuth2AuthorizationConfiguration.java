package com.pyeonit.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
class OAuth2AuthorizationConfiguration {
  @Bean
  @Conditional(OAuth2ClientConfig.ProvidersConfigured.class)
  OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository clients) {
    var delegate = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    var authorizationPath = PathPatternRequestMatcher.withDefaults()
        .matcher("/oauth2/authorization/{registrationId}");
    // OAuth2LoginConfigurer discovers this bean. Unregistered providers fall through to MVC's 404.
    return new OAuth2AuthorizationRequestResolver() {
      @Override
      public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        var match = authorizationPath.matcher(request);
        if (match.isMatch() && clients.findByRegistrationId(match.getVariables().get("registrationId")) == null) {
          return null;
        }
        return delegate.resolve(request);
      }

      @Override
      public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        if (registrationId == null || clients.findByRegistrationId(registrationId) == null) return null;
        return delegate.resolve(request, registrationId);
      }
    };
  }
}
