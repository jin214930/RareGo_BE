package com.bugzero.rarego.domain;

public record AccountDto (
	String providerId,
	String email,
	Provider provider
) {
}
