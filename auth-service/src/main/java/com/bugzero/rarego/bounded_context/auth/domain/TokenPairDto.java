package com.bugzero.rarego.bounded_context.auth.domain;

public record TokenPairDto(
	String accessToken,
	String refreshToken
) {
}
