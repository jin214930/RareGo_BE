package com.bugzero.rarego.global.security;

import org.springframework.security.core.AuthenticationException;

import com.bugzero.rarego.global.response.ErrorType;

public class JwtAuthenticationException extends AuthenticationException {
	private final ErrorType errorType;

	public JwtAuthenticationException(ErrorType errorType) {
		super(errorType == null ? "JWT authentication error" : errorType.getMessage());
		this.errorType = errorType;
	}

	public ErrorType getErrorType() {
		return errorType;
	}
}
