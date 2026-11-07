package com.pyeonit.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.pyeonit.config.ApiException;

class ActiveUserGuardTests {

	UserRepository users = mock(UserRepository.class);

	ActiveUserGuard guard = new ActiveUserGuard(users);

	static Authentication as(String name) {
		return UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
	}

	User user(User.Status status) {
		User u = new User("nick", User.Role.USER);
		u.status = status;
		return u;
	}

	@Test
	void activePasses() {
		when(users.findById(1L)).thenReturn(Optional.of(user(User.Status.ACTIVE)));
		assertThatCode(() -> guard.requireActive(as("1"))).doesNotThrowAnyException();
	}

	@Test
	void suspendedIsForbidden() {
		when(users.findById(2L)).thenReturn(Optional.of(user(User.Status.SUSPENDED)));
		assertThatThrownBy(() -> guard.requireActive(as("2"))).isInstanceOfSatisfying(ApiException.class, e -> {
			org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
			org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("ACCOUNT_SUSPENDED");
		});
	}

	@Test
	void pendingSignupIsSignupRequired() {
		assertThatThrownBy(() -> guard.requireActive(as("pending:kakao:1")))
			.isInstanceOfSatisfying(ApiException.class,
					e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("SIGNUP_REQUIRED"));
	}

	@Test
	void withdrawnOrMissingIsUnauthorized() {
		when(users.findById(3L)).thenReturn(Optional.of(user(User.Status.WITHDRAWN)));
		when(users.findById(4L)).thenReturn(Optional.empty());
		for (String name : List.of("3", "4")) {
			assertThatThrownBy(() -> guard.requireActive(as(name))).isInstanceOfSatisfying(ApiException.class,
					e -> org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
		}
	}

}
