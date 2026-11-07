package com.pyeonit.auth;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pyeonit.config.ApiException;
import com.pyeonit.config.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
class AuthController {

	/** 소셜 연결 의도: "<userId>:<registrationId>". OAuth2LoginHandler 가 읽고 지운다. */
	static final String LINK_INTENT = AuthController.class.getName() + ".LINK";

	record NicknameRequest(
			@NotNull(message = "닉네임을 입력해 주세요")
			@Pattern(regexp = "^[가-힣a-zA-Z0-9]{2,12}$", message = "닉네임은 2~12자의 한글·영문·숫자만 쓸 수 있습니다")
			String nickname) {
	}

	record SocialLinkStart(String authorizationUrl) {
	}

	record NicknameChangeTooSoonError(String code, String message, List<ErrorResponse.FieldError> fieldErrors,
			OffsetDateTime nicknameChangeableAt) {
	}

	private final AuthService auth;

	private final ObjectProvider<ClientRegistrationRepository> clients;

	AuthController(AuthService auth, ObjectProvider<ClientRegistrationRepository> clients) {
		this.auth = auth;
		this.clients = clients;
	}

	@PostMapping("/api/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	AuthService.Me signup(@Valid @RequestBody NicknameRequest body, Authentication authentication,
			HttpServletRequest req, HttpServletResponse res) {
		User user = auth.signup(authentication, body.nickname());
		req.changeSessionId();
		AuthService.startSession(AuthService.memberAuthentication(user), req, res);
		return auth.me(user);
	}

	@GetMapping("/api/me")
	AuthService.Me me(Authentication authentication) {
		return auth.me(auth.member(authentication));
	}

	@PatchMapping("/api/me/nickname")
	AuthService.Me changeNickname(@Valid @RequestBody NicknameRequest body, Authentication authentication) {
		return auth.me(auth.changeNickname(authentication, body.nickname()));
	}

	@DeleteMapping("/api/me")
	ResponseEntity<Void> withdraw(Authentication authentication, HttpServletRequest req) {
		// 가입 대기 세션은 users 행이 없으니 세션만 끝낸다
		if (!authentication.getName().startsWith("pending:")) {
			auth.withdraw(auth.member(authentication));
		}
		HttpSession session = req.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/me/social-accounts/{provider}")
	SocialLinkStart startSocialLink(@PathVariable String provider, Authentication authentication,
			HttpServletRequest req) {
		User user = auth.member(authentication);
		SocialAccount.Provider p = AuthService.provider(provider);
		ClientRegistrationRepository registrations = clients.getIfAvailable();
		if (registrations == null || registrations.findByRegistrationId(provider) == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "지원하지 않는 로그인 제공자입니다");
		}
		if (auth.hasProvider(user.id, p)) {
			throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_ALREADY_LINKED", "이미 연결된 로그인 수단입니다");
		}
		req.getSession().setAttribute(LINK_INTENT, user.id + ":" + provider);
		return new SocialLinkStart("/oauth2/authorization/" + provider);
	}

	@DeleteMapping("/api/me/social-accounts/{provider}")
	ResponseEntity<Void> unlinkSocialAccount(@PathVariable String provider, Authentication authentication) {
		auth.unlink(auth.member(authentication), provider);
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(AuthService.NicknameChangeTooSoon.class)
	ResponseEntity<NicknameChangeTooSoonError> tooSoon(AuthService.NicknameChangeTooSoon e) {
		long seconds = Math.max(1, Duration.between(OffsetDateTime.now(), e.changeableAt).toSeconds() + 1);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
				.body(new NicknameChangeTooSoonError("NICKNAME_CHANGE_TOO_SOON", "닉네임은 30일에 한 번만 바꿀 수 있습니다",
						List.of(), e.changeableAt));
	}

}
