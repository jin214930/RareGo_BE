package com.bugzero.rarego.in.dto;

import jakarta.validation.constraints.Positive;

public record WithdrawRequestDto(
	@Positive(message = "출금 금액은 1원 이상이어야 합니다.")
	int amount
) {
}
