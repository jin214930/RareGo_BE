package com.bugzero.rarego.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomOAuth2FailureHandler implements AuthenticationFailureHandler {
	@Value("${custom.global.frontUrl}")
	private String frontUrl;

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
		AuthenticationException exception) throws IOException {
		ErrorType errorType = resolveErrorType(exception);
		String errorMessage = resolveErrorMessage(exception, errorType);

		String targetUrl = UriComponentsBuilder.fromUriString(frontUrl + "/auth/callback")
			.queryParam("errorCode", errorType.getCode())
			.queryParam("errorMessage", errorMessage)
			.build().toUriString();

		response.sendRedirect(targetUrl);
	}

	private ErrorType resolveErrorType(AuthenticationException exception) {
		if (exception instanceof OAuth2AuthenticationException oauthException) {
			OAuth2Error error = oauthException.getError();
			ErrorType fromError = resolveErrorTypeFromOAuth2Error(error);
			if (fromError != null) {
				return fromError;
			}
		}
		if (exception.getCause() instanceof CustomException customException) {
			return customException.getErrorType();
		}
		return ErrorType.AUTH_OAUTH2_INVALID_RESPONSE;
	}

	private ErrorType resolveErrorTypeFromOAuth2Error(OAuth2Error error) {
		if (error == null || error.getErrorCode() == null) {
			return null;
		}
		String code = error.getErrorCode();
		try {
			int numericCode = Integer.parseInt(code);
			return ErrorType.findByCode(numericCode).orElse(null);
		} catch (NumberFormatException ignored) {
			// fall through
		}
		try {
			return ErrorType.valueOf(code);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private String resolveErrorMessage(AuthenticationException exception, ErrorType errorType) {
		if (exception instanceof OAuth2AuthenticationException oauthException) {
			OAuth2Error error = oauthException.getError();
			if (error != null && error.getDescription() != null && !error.getDescription().isBlank()) {
				return error.getDescription();
			}
		}
		if (exception.getCause() instanceof CustomException customException) {
			String message = customException.getMessage();
			if (message != null && !message.isBlank()) {
				return message;
			}
		}
		return errorType.getMessage();
	}
}
