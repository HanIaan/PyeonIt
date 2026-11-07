package com.pyeonit.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pyeonit.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

class DevLoginTests {

	static RequestPostProcessor csrf(MockMvc mvc) throws Exception {
		Cookie cookie = mvc.perform(get("/api/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return request -> {
			request.setCookies(cookie);
			request.addHeader("X-XSRF-TOKEN", cookie.getValue());
			return request;
		};
	}

	@Nested
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("dev")
	class DevProfile {

		@Autowired
		MockMvc mvc;

		@Autowired
		org.springframework.jdbc.core.JdbcTemplate jdbc;

		@Autowired
		ActiveUserGuard guard;

		@Test
		void createsOrReusesUserAndOpensSession() throws Exception {
			MockHttpSession session = new MockHttpSession();
			String body = "{\"nickname\":\"devadmin\",\"role\":\"ADMIN\"}";
			String id = mvc.perform(post("/api/dev/login").session(session).with(csrf(mvc))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("devadmin"))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1");

			mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(Long.parseLong(id)));

			// 같은 닉네임이면 같은 회원, 기본 역할 USER
			mvc.perform(post("/api/dev/login").with(csrf(mvc)).contentType(MediaType.APPLICATION_JSON)
					.content("{\"nickname\":\"devadmin\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(Long.parseLong(id)))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		}

		@Test
		void suspendedFlagCreatesSuspendedUser() throws Exception {
			MockHttpSession session = new MockHttpSession();
			mvc.perform(post("/api/dev/login").session(session).with(csrf(mvc))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"nickname\":\"devbanned\",\"role\":\"ADMIN\",\"suspended\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.status").value("SUSPENDED"));
			mvc.perform(get("/api/me").session(session)).andExpect(jsonPath("$.status").value("SUSPENDED"));

			// 정지 회원 세션은 ActiveUserGuard 에서 막힌다
			Long id = jdbc.queryForObject("SELECT id FROM users WHERE nickname = 'devbanned'", Long.class);
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.requireActive(
					org.springframework.security.authentication.UsernamePasswordAuthenticationToken
						.authenticated(String.valueOf(id), null, java.util.List.of())))
				.hasMessageContaining("정지");
		}

	}

	@Nested
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@AutoConfigureMockMvc
	class DefaultProfile {

		@Autowired
		MockMvc mvc;

		@Test
		void endpointDoesNotExist() throws Exception {
			mvc.perform(post("/api/dev/login").with(csrf(mvc)).contentType(MediaType.APPLICATION_JSON)
					.content("{\"nickname\":\"devuser\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}

	}

}
