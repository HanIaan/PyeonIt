package com.pyeonit.config;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.servlet.http.Cookie;

import com.pyeonit.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTests {

	@Autowired
	MockMvc mvc;

	// spring-security-test 의 csrf() 는 공유 컨텍스트의 CsrfFilter 저장소를 바꿔치기하므로, 실제 /api/csrf 토큰을 쿠키+헤더로 보낸다
	private RequestPostProcessor csrf() throws Exception {
		Cookie cookie = mvc.perform(get("/api/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return request -> {
			request.setCookies(cookie);
			request.addHeader("X-XSRF-TOKEN", cookie.getValue());
			return request;
		};
	}

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void anonymousGetPassesSecurity() throws Exception {
		mvc.perform(get("/api/does-not-exist"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.fieldErrors").isArray());
	}

	@Test
	void meRequiresLogin() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void writeWithoutCsrfIsRejected() throws Exception {
		mvc.perform(post("/api/posts").with(user("u").roles("USER")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_INVALID"));
	}

	@Test
	void anonymousWriteRequiresLogin() throws Exception {
		mvc.perform(post("/api/posts").with(csrf()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void pendingSignupWriteRequiresSignup() throws Exception {
		mvc.perform(post("/api/posts").with(user("pending").authorities(() -> "OAUTH2_USER")).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
	}

	@Test
	void memberWritePassesSecurity() throws Exception {
		mvc.perform(post("/api/posts").with(user("u").roles("USER")).with(csrf()))
			.andExpect(status().is(not(401)))
			.andExpect(status().is(not(403)));
	}

	@Test
	void adminPathsNeedAdmin() throws Exception {
		mvc.perform(get("/api/admin/reports").with(user("u").roles("USER")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(get("/api/admin/reports").with(user("a").roles("ADMIN")))
			.andExpect(status().isNotFound());
	}

	@Test
	@org.springframework.security.test.context.support.WithMockUser(username = "1", roles = "ADMIN")
	void adminInheritsUserRole() throws Exception {
		mvc.perform(get("/api/me/favorite-universities"))
			.andExpect(status().isOk());
	}

	@Test
	void devLoginIsNotPublicOutsideDevProfile() throws Exception {
		mvc.perform(post("/api/dev/login").with(csrf())).andExpect(status().isUnauthorized());
	}

	@Test
	void csrfEndpointReturnsTokenAndCookie() throws Exception {
		mvc.perform(get("/api/csrf"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
			.andExpect(jsonPath("$.token").isNotEmpty())
			.andExpect(cookie().exists("XSRF-TOKEN"))
			.andExpect(cookie().httpOnly("XSRF-TOKEN", false))
			.andExpect(cookie().sameSite("XSRF-TOKEN", "Lax"));
	}

	@Test
	void logoutReturnsNoContent() throws Exception {
		mvc.perform(post("/api/auth/logout").with(user("u").roles("USER")).with(csrf()))
			.andExpect(status().isNoContent());
	}

	@Test
	void corsAllowsWebDevOriginWithCredentials() throws Exception {
		mvc.perform(options("/api/posts")
				.header("Origin", "http://localhost:3000")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "X-XSRF-TOKEN"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
		mvc.perform(options("/api/posts")
				.header("Origin", "http://evil.example")
				.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().isForbidden());
	}

}
