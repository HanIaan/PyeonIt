package com.pyeonit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pyeonit.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

/** 제공자 인가·토큰 호출은 하지 않고, 인증이 끝난 OAuth2AuthenticationToken 을 성공 핸들러에 직접 넣는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "pyeonit.oauth2.registration.kakao.client-id=test-kakao",
		"pyeonit.oauth2.registration.google.client-id=test-google" })
@AutoConfigureMockMvc
class AuthFlowTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	OAuth2LoginHandler handler;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	// ===== 도우미 =====

	record Login(MockHttpSession session, String redirect) {
		Authentication auth() {
			SecurityContext ctx = (SecurityContext) session
				.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
			return ctx == null ? null : ctx.getAuthentication();
		}
	}

	/** 제공자에서 돌아온 직후(OAuth2LoginAuthenticationFilter 가 성공 핸들러를 부르는 시점)를 흉내 낸다. */
	Login oauth(String provider, String providerUserId, MockHttpSession session) throws Exception {
		var authorities = AuthorityUtils.createAuthorityList("OAUTH2_USER");
		var token = new OAuth2AuthenticationToken(
				new DefaultOAuth2User(authorities, Map.of("id", providerUserId), "id"), authorities, provider);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setSession(session == null ? new MockHttpSession() : session);
		MockHttpServletResponse res = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(req, res, token);
		SecurityContextHolder.clearContext();
		return new Login((MockHttpSession) req.getSession(), res.getRedirectedUrl());
	}

	static String uid() {
		return UUID.randomUUID().toString();
	}

	static String nick() {
		return "n" + (System.nanoTime() % 100_000_000) + SEQ.incrementAndGet() % 10;
	}

	RequestPostProcessor csrf() throws Exception {
		Cookie cookie = mvc.perform(get("/api/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return request -> {
			request.setCookies(cookie);
			request.addHeader("X-XSRF-TOKEN", cookie.getValue());
			return request;
		};
	}

	ResultActions send(MockHttpServletRequestBuilder req, MockHttpSession session, String json) throws Exception {
		req.session(session).with(csrf());
		if (json != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(req);
	}

	ResultActions signup(MockHttpSession session, String nickname) throws Exception {
		return send(post("/api/auth/signup"), session, "{\"nickname\":\"" + nickname + "\"}");
	}

	ResultActions changeNickname(MockHttpSession session, String nickname) throws Exception {
		return send(patch("/api/me/nickname"), session, "{\"nickname\":\"" + nickname + "\"}");
	}

	/** 카카오로 가입한 회원 세션. */
	Login member(String kakaoId, String nickname) throws Exception {
		Login login = oauth("kakao", kakaoId, null);
		signup(login.session(), nickname).andExpect(status().isCreated());
		return login;
	}

	long userId(Login login) {
		return Long.parseLong(login.auth().getName());
	}

	// ===== 가입 대기 =====

	@Test
	void firstLoginCreatesPendingSession() throws Exception {
		Login login = oauth("kakao", "123456", null);

		assertThat(login.redirect()).isEqualTo("http://localhost:3000/signup");
		assertThat(login.auth().getName()).isEqualTo("pending:kakao:123456");
		assertThat(login.auth().getAuthorities()).isEmpty();
	}

	@Test
	void pendingSessionGetsSignupRequired() throws Exception {
		MockHttpSession pending = oauth("google", uid(), null).session();

		mvc.perform(get("/api/me").session(pending))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
		changeNickname(pending, nick()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
		send(post("/api/me/social-accounts/google"), pending, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
		// users.id 가 필요한 다른 도메인 쓰기
		send(post("/api/posts"), pending, "{}").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
	}

	@Test
	void signupReplacesPrincipalWithUserId() throws Exception {
		String kakaoId = uid();
		String nickname = nick();
		Login login = oauth("kakao", kakaoId, null);
		String sessionBefore = login.session().getId();

		signup(login.session(), nickname)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.nickname").value(nickname))
			.andExpect(jsonPath("$.role").value("USER"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.nicknameChangeableAt").value(nullValue()))
			.andExpect(jsonPath("$.suspendedUntil").value(nullValue()))
			.andExpect(jsonPath("$.socialAccounts[0].provider").value("KAKAO"))
			.andExpect(jsonPath("$.createdAt").isNotEmpty());

		Authentication auth = login.auth();
		assertThat(auth.getName()).matches("\\d+");
		assertThat(AuthorityUtils.authorityListToSet(auth.getAuthorities())).containsExactly("ROLE_USER");
		assertThat(login.session().getId()).isNotEqualTo(sessionBefore);
		mvc.perform(get("/api/me").session(login.session()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(Long.parseLong(auth.getName())));

		signup(login.session(), nick()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_REGISTERED"));
	}

	@Test
	void returningSocialAccountLogsInAsMember() throws Exception {
		String kakaoId = uid();
		long id = userId(member(kakaoId, nick()));

		Login again = oauth("kakao", kakaoId, null);

		assertThat(again.redirect()).isEqualTo("http://localhost:3000/");
		assertThat(again.auth().getName()).isEqualTo(String.valueOf(id));
	}

	@Test
	void adminRoleFollowsUsersRole() throws Exception {
		String kakaoId = uid();
		long id = userId(member(kakaoId, nick()));
		jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", id);

		Login again = oauth("kakao", kakaoId, null);

		assertThat(AuthorityUtils.authorityListToSet(again.auth().getAuthorities())).containsExactly("ROLE_ADMIN");
		mvc.perform(get("/api/me").session(again.session())).andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void failureRedirectsToLoginWithCode() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		handler.onAuthenticationFailure(new MockHttpServletRequest(), res,
				new org.springframework.security.oauth2.core.OAuth2AuthenticationException("access_denied"));
		assertThat(res.getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=OAUTH2_FAILED");
	}

	// ===== 닉네임 규칙 =====

	@Test
	void nicknameFormatIsValidated() throws Exception {
		MockHttpSession pending = oauth("kakao", uid(), null).session();
		for (String bad : List.of("a", "abcdefghijklm", "닉네임!", "nick name", "ㅋㅋㅋ", "")) {
			signup(pending, bad).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"));
		}
		send(post("/api/auth/signup"), pending, "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		signup(pending, "가나12abCD").andExpect(status().isCreated());
	}

	@Test
	void nicknameIsUniqueIgnoringCase() throws Exception {
		String base = "Dup" + (System.nanoTime() % 1_000_000);
		member(uid(), base);

		signup(oauth("kakao", uid(), null).session(), base.toLowerCase()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));

		Login other = member(uid(), nick());
		changeNickname(other.session(), base.toUpperCase()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));
	}

	@Test
	void forbiddenWordsAreRejected() throws Exception {
		MockHttpSession pending = oauth("kakao", uid(), null).session();
		for (String bad : List.of("운영자", "편잇지기", "PyeonIt12", "superADMIN", "관리자님")) {
			signup(pending, bad).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("NICKNAME_FORBIDDEN"));
		}
		Login m = member(uid(), nick());
		changeNickname(m.session(), "Admin1").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("NICKNAME_FORBIDDEN"));
	}

	@Test
	void nicknameChangeOncePer30Days() throws Exception {
		Login m = member(uid(), nick());
		long id = userId(m);

		// 가입 시 최초 설정은 이력이 아니므로 바로 변경 가능
		String first = nick();
		changeNickname(m.session(), first).andExpect(status().isOk())
			.andExpect(jsonPath("$.nickname").value(first))
			.andExpect(jsonPath("$.nicknameChangeableAt").isNotEmpty());

		changeNickname(m.session(), nick()).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("NICKNAME_CHANGE_TOO_SOON"))
			.andExpect(jsonPath("$.fieldErrors").isArray())
			.andExpect(jsonPath("$.nicknameChangeableAt").isNotEmpty())
			.andExpect(header().exists("Retry-After"));

		// 같은 닉네임은 제한 안에서도 변경 없이 200
		changeNickname(m.session(), first).andExpect(status().isOk());

		// 경계: 30일에서 1분 모자람 → 429, Retry-After 약 60초
		jdbc.update("UPDATE users SET nickname_changed_at = now() - interval '30 days' + interval '1 minute' WHERE id = ?", id);
		String retry = changeNickname(m.session(), nick()).andExpect(status().isTooManyRequests())
			.andReturn().getResponse().getHeader("Retry-After");
		assertThat(Long.parseLong(retry)).isBetween(1L, 61L);

		// 경계: 30일 지남 → 변경 가능
		jdbc.update("UPDATE users SET nickname_changed_at = now() - interval '30 days' - interval '1 second' WHERE id = ?", id);
		changeNickname(m.session(), nick()).andExpect(status().isOk());
	}

	@Test
	void suspendedMemberCanReadAndChangeNickname() throws Exception {
		Login m = member(uid(), nick());
		jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userId(m));

		mvc.perform(get("/api/me").session(m.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		changeNickname(m.session(), nick()).andExpect(status().isOk());
	}

	// ===== 소셜 계정 연결·해제 =====

	@Test
	void linkAndUnlinkSocialAccounts() throws Exception {
		Login m = member(uid(), nick());
		long id = userId(m);

		send(post("/api/me/social-accounts/kakao"), m.session(), null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROVIDER_ALREADY_LINKED"));
		send(delete("/api/me/social-accounts/kakao"), m.session(), null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LAST_SOCIAL_ACCOUNT"));
		send(delete("/api/me/social-accounts/google"), m.session(), null).andExpect(status().isNotFound());

		send(post("/api/me/social-accounts/google"), m.session(), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google"));
		Login linked = oauth("google", uid(), m.session());

		assertThat(linked.redirect()).isEqualTo("http://localhost:3000/me?linked=google");
		assertThat(linked.auth().getName()).isEqualTo(String.valueOf(id));
		mvc.perform(get("/api/me").session(m.session()))
			.andExpect(jsonPath("$.socialAccounts.length()").value(2));

		send(delete("/api/me/social-accounts/kakao"), m.session(), null).andExpect(status().isNoContent());
		send(delete("/api/me/social-accounts/google"), m.session(), null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LAST_SOCIAL_ACCOUNT"));
	}

	@Test
	void linkingAccountOfAnotherMemberFails() throws Exception {
		String othersGoogle = uid();
		Login other = oauth("google", othersGoogle, null);
		signup(other.session(), nick()).andExpect(status().isCreated());
		Login m = member(uid(), nick());

		send(post("/api/me/social-accounts/google"), m.session(), null).andExpect(status().isOk());
		Login result = oauth("google", othersGoogle, m.session());

		assertThat(result.redirect()).isEqualTo("http://localhost:3000/me?linkError=SOCIAL_ACCOUNT_IN_USE");
		assertThat(result.auth().getName()).isEqualTo(String.valueOf(userId(m)));
	}

	// ===== 탈퇴 =====

	@Test
	void withdrawRemovesAllSocialAccountsAndKeepsPosts() throws Exception {
		String kakaoId = uid();
		String googleId = uid();
		Login m = member(kakaoId, nick());
		long id = userId(m);
		send(post("/api/me/social-accounts/google"), m.session(), null).andExpect(status().isOk());
		oauth("google", googleId, m.session());
		jdbc.update("INSERT INTO posts (board_id, author_id, title, body) SELECT id, ?, 't', 'b' FROM boards LIMIT 1", id);

		send(delete("/api/me"), m.session(), null).andExpect(status().isNoContent());

		assertThat(m.session().isInvalid()).isTrue();
		Map<String, Object> row = jdbc.queryForMap("SELECT nickname, status, withdrawn_at FROM users WHERE id = ?", id);
		assertThat(row.get("nickname")).isNull();
		assertThat(row.get("status")).isEqualTo("WITHDRAWN");
		assertThat(row.get("withdrawn_at")).isNotNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM social_accounts WHERE user_id = ?", Long.class, id)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM posts WHERE author_id = ?", Long.class, id)).isOne();

		// 같은 소셜 계정으로 다시 로그인하면 기존 회원이 아니라 신규 가입 흐름
		Login again = oauth("kakao", kakaoId, null);
		assertThat(again.redirect()).isEqualTo("http://localhost:3000/signup");
		assertThat(again.auth().getName()).isEqualTo("pending:kakao:" + kakaoId);
		signup(again.session(), nick()).andExpect(status().isCreated());
		assertThat(userId(again)).isNotEqualTo(id);
	}

	@Test
	void withdrawnMembersOldSessionIsUnauthorized() throws Exception {
		Login m = member(uid(), nick());
		jdbc.update("UPDATE users SET status = 'WITHDRAWN', nickname = NULL WHERE id = ?", userId(m));

		mvc.perform(get("/api/me").session(m.session())).andExpect(status().isUnauthorized());
	}

}
