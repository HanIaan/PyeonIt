package com.pyeonit.config;

import java.util.List;

/** 모든 오류 응답 본문. api/openapi.yaml 의 ErrorResponse 와 같다. */
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

}
