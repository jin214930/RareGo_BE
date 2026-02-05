package com.bugzero.rarego.in.dto;

import com.bugzero.rarego.domain.Payment;

public record PaymentRequestResponseDto(
	String orderId,
	int amount,
	String customerName,
	String customerEmail
) {
	public static PaymentRequestResponseDto from(Payment payment) {
		return new PaymentRequestResponseDto(
			payment.getOrderId(),
			payment.getAmount(),
			payment.getMember().getNickname(),
			payment.getMember().getEmail()
		);
	}
}
