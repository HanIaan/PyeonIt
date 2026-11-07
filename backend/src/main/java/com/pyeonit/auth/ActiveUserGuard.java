package com.pyeonit.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.pyeonit.config.ApiException;

/**
 * 정지(SUSPENDED) 회원 쓰기 차단. x-suspended-blocked operation 의 컨트롤러가 먼저 호출한다.
 * 매번 DB 를 읽으므로 세션 중 정지돼도 바로 막힌다.
 */
@Component
public class ActiveUserGuard {

	private final UserRepository users;

	public ActiveUserGuard(UserRepository users) {
		this.users = users;
	}

	/** 가입 대기 403 SIGNUP_REQUIRED, 탈퇴·없음 401 UNAUTHORIZED, 정지 403 ACCOUNT_SUSPENDED. */
	public void requireActive(Authentication authentication) {
		User user = users.findById(AuthService.memberId(authentication))
				.filter(u -> u.getStatus() != User.Status.WITHDRAWN)
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다"));
		if (user.getStatus() == User.Status.SUSPENDED) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "정지된 계정은 이 기능을 쓸 수 없습니다");
		}
	}

}
