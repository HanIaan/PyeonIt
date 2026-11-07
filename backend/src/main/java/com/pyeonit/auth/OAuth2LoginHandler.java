package com.pyeonit.auth;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * OAuth2 로그인 결과를 세션으로 바꾸고 웹으로 보낸다 (api/README.md (a)절).
 * 로그인: 회원 {WEB}/, 가입 대기 {WEB}/signup, 실패 {WEB}/login?error=OAUTH2_FAILED.
 * 연결(POST /api/me/social-accounts/{provider} 뒤): {WEB}/me?linked={provider} 또는 ?linkError={code}, 세션은 원래 회원으로 되돌린다.
 */
@Component
public class OAuth2LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

	private final AuthService auth;

	private final UserRepository users;

	private final String webBaseUrl;

	OAuth2LoginHandler(AuthService auth, UserRepository users,
			@Value("${pyeonit.web-base-url:http://localhost:3000}") String webBaseUrl) {
		this.auth = auth;
		this.users = users;
		this.webBaseUrl = webBaseUrl;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication authentication)
			throws IOException {
		String provider = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
		String providerUserId = authentication.getName();
		String intent = takeLinkIntent(req);
		if (intent != null && intent.endsWith(":" + provider)) {
			long userId = Long.parseLong(intent.substring(0, intent.indexOf(':')));
			String error = auth.link(userId, provider, providerUserId);
			User user = users.findById(userId).filter(u -> u.status != User.Status.WITHDRAWN).orElse(null);
			if (user == null) {
				res.sendRedirect(webBaseUrl + "/login?error=OAUTH2_FAILED");
				return;
			}
			AuthService.startSession(AuthService.memberAuthentication(user), req, res);
			res.sendRedirect(webBaseUrl + "/me?" + (error == null ? "linked=" + provider : "linkError=" + error));
			return;
		}
		Authentication session = auth.login(provider, providerUserId);
		AuthService.startSession(session, req, res);
		res.sendRedirect(webBaseUrl + (session.getName().startsWith("pending:") ? "/signup" : "/"));
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res, AuthenticationException e)
			throws IOException {
		boolean linking = takeLinkIntent(req) != null;
		res.sendRedirect(webBaseUrl + (linking ? "/me?linkError=OAUTH2_FAILED" : "/login?error=OAUTH2_FAILED"));
	}

	private static String takeLinkIntent(HttpServletRequest req) {
		HttpSession session = req.getSession(false);
		if (session == null) {
			return null;
		}
		String intent = (String) session.getAttribute(AuthController.LINK_INTENT);
		session.removeAttribute(AuthController.LINK_INTENT);
		return intent;
	}

}
