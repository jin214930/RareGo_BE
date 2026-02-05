package com.bugzero.rarego.in.dto;

public record TossPaymentsConfirmResponseDto(
	String orderId,
	String paymentKey,
	Integer totalAmount
) {
}
