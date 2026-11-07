package com.pyeonit.config;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/csrf: 토큰을 본문으로 돌려주고, 토큰을 읽는 순간 XSRF-TOKEN 쿠키도 설정된다. */
@RestController
class CsrfController {

	record CsrfTokenResponse(String headerName, String token) {
	}

	@GetMapping("/api/csrf")
	CsrfTokenResponse csrf(CsrfToken token) {
		return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
	}

}
