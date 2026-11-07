package com.pyeonit.config;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 도메인 코드가 던지는 API 오류. code 는 api/README.md 코드 표의 값.
 * 예: throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다");
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final List<ErrorResponse.FieldError> fieldErrors;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, List.of());
	}

	public ApiException(HttpStatus status, String code, String message, List<ErrorResponse.FieldError> fieldErrors) {
		super(message);
		this.status = status;
		this.code = code;
		this.fieldErrors = fieldErrors;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public List<ErrorResponse.FieldError> getFieldErrors() {
		return fieldErrors;
	}

}
