package com.bugzero.rarego.domain;

public record TokenPairDto(
	String accessToken,
	String refreshToken
) {
}
