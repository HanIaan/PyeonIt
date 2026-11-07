package com.pyeonit.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** dev 프로필에서만 빈이 생긴다. 다른 프로필에서 POST /api/dev/login 은 404. */
@Profile("dev")
@RestController
class DevLoginController {

	record DevLoginRequest(@NotNull @Size(min = 2, max = 12) String nickname, User.Role role, Boolean suspended) {
	}

	private final AuthService auth;

	DevLoginController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/api/dev/login")
	AuthService.Me login(@Valid @RequestBody DevLoginRequest body, HttpServletRequest req, HttpServletResponse res) {
		User user = auth.devUser(body.nickname(), body.role() == null ? User.Role.USER : body.role(),
				Boolean.TRUE.equals(body.suspended()));
		req.getSession();
		req.changeSessionId();
		AuthService.startSession(AuthService.memberAuthentication(user), req, res);
		return auth.me(user);
	}

}
