package com.bugzero.rarego.domain;

import jakarta.validation.constraints.Size;

public record MemberUpdateIdentityRequestDto(
	@Size(max = 10)
	String realName,

	@Size(max = 11)
	String contactPhone
) {
}
