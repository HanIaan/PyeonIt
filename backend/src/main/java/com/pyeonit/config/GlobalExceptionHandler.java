package com.pyeonit.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** 모든 오류를 ErrorResponse 로 바꾼다. 코드 표는 api/README.md. Security 필터의 401·403 도 SecurityConfig 가 여기로 넘긴다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> api(ApiException e) {
		return error(e.getStatus(), e.getCode(), e.getMessage(), e.getFieldErrors());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
		List<ErrorResponse.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
				.map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
				.toList();
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요", fields);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ErrorResponse> invalidParams(HandlerMethodValidationException e) {
		List<ErrorResponse.FieldError> fields = e.getParameterValidationResults().stream()
				.flatMap(r -> r.getResolvableErrors().stream()
						.map(err -> new ErrorResponse.FieldError(r.getMethodParameter().getParameterName(),
								err.getDefaultMessage())))
				.toList();
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요", fields);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, TypeMismatchException.class })
	ResponseEntity<ErrorResponse> unreadable(Exception e) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다", List.of());
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ErrorResponse> unauthenticated(AuthenticationException e) {
		return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다", List.of());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> denied(AccessDeniedException e) {
		if (e instanceof CsrfException) {
			return error(HttpStatus.FORBIDDEN, "CSRF_INVALID", "보안 토큰이 없거나 만료됐습니다. 새로고침 후 다시 시도해 주세요", List.of());
		}
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth instanceof AnonymousAuthenticationToken) {
			return unauthenticated(null);
		}
		boolean member = auth.getAuthorities().stream()
				.anyMatch(a -> "ROLE_USER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
		if (!member) {
			return error(HttpStatus.FORBIDDEN, "SIGNUP_REQUIRED", "닉네임을 설정해 가입을 완료해 주세요", List.of());
		}
		return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다", List.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> other(Exception e) {
		// 404·405·413·415 등 Spring MVC 가 상태 코드를 정한 예외
		if (!(e instanceof org.springframework.web.ErrorResponse framework)) {
			log.error("처리되지 않은 오류", e);
			return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다", List.of());
		}
		HttpStatusCode status = framework.getStatusCode();
		String code = switch (status.value()) {
			case 400 -> "INVALID_REQUEST";
			case 404 -> "NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 413 -> "PAYLOAD_TOO_LARGE";
			case 415 -> "UNSUPPORTED_MEDIA_TYPE";
			default -> "HTTP_" + status.value();
		};
		String message = status.value() == 404 ? "요청한 대상을 찾을 수 없습니다" : "요청을 처리할 수 없습니다";
		return error(status, code, message, List.of());
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatusCode status, String code, String message,
			List<ErrorResponse.FieldError> fields) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message, fields));
	}

}
