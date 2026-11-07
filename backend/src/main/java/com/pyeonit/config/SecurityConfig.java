package com.pyeonit.config;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 경로 규칙은 api/openapi.yaml 과 맞춘다. 회원 세션의 권한 계약(B1 구현):
 * 가입 완료 회원은 users.role 에 따라 ROLE_USER 또는 ROLE_ADMIN, 닉네임 미설정(가입 대기) 세션은 둘 다 없다.
 */
@Configuration
public class SecurityConfig {

	@Value("${pyeonit.cors.allowed-origins:http://localhost:3000}")
	private List<String> allowedOrigins;

	@Value("${pyeonit.web-base-url:http://localhost:3000}")
	private String webBaseUrl;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, Environment env,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
			ObjectProvider<ClientRegistrationRepository> oauth2Clients) throws Exception {
		boolean dev = env.matchesProfiles("dev");
		http.authorizeHttpRequests(auth -> {
			auth.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
				.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				// 가입 대기 세션도 내 정보 조회(→ SIGNUP_REQUIRED)·가입·탈퇴는 컨트롤러까지 보낸다
				.requestMatchers("/api/me", "/api/auth/signup").authenticated()
				.requestMatchers("/api/me/**").hasRole("USER")
				.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll();
			if (dev) {
				auth.requestMatchers(HttpMethod.POST, "/api/dev/login").permitAll();
			}
			auth.anyRequest().hasRole("USER");
		});

		CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
		// 헤더로만 받는 JSON API라 BREACH 대비 마스킹(Xor)이 필요 없고, /api/csrf 본문 토큰을 그대로 헤더에 쓸 수 있게 평문 처리
		http.csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));

		http.cors(cors -> {});
		http.logout(logout -> logout.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
		// 401·403(CSRF 포함)도 GlobalExceptionHandler 가 같은 ErrorResponse 형식으로 쓴다
		http.exceptionHandling(ex -> ex
				.authenticationEntryPoint((req, res, e) -> resolver.resolveException(req, res, null, e))
				.accessDeniedHandler((req, res, e) -> resolver.resolveException(req, res, null, e)));

		// 제공자 등록(spring.security.oauth2.client.registration.*)이 있을 때만 켠다. 실제 동작은 B1 이 handler 를 채운다.
		if (oauth2Clients.getIfAvailable() != null) {
			http.oauth2Login(oauth2 -> oauth2
					.successHandler(oauth2SuccessHandler())
					.failureHandler(oauth2FailureHandler()));
		}
		return http.build();
	}

	// B1: 가입 대기면 {WEB}/signup, 회원이면 {WEB}/ 로 보낸다
	private AuthenticationSuccessHandler oauth2SuccessHandler() {
		return (req, res, auth) -> res.sendRedirect(webBaseUrl + "/");
	}

	// B1: 실패 사유별 code 로 바꾼다
	private AuthenticationFailureHandler oauth2FailureHandler() {
		return (req, res, e) -> res.sendRedirect(webBaseUrl + "/login?error=OAUTH2_FAILED");
	}

	@Bean
	static RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > ROLE_USER");
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

	// 세션 쿠키 SameSite=Lax. HttpOnly 는 Tomcat 기본값, 운영 Secure 는 SERVER_SERVLET_SESSION_COOKIE_SECURE=true
	@Bean
	CookieSameSiteSupplier sessionCookieSameSite() {
		return CookieSameSiteSupplier.ofLax().whenHasName("JSESSIONID");
	}

}
