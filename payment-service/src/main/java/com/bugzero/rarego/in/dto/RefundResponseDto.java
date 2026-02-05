package com.bugzero.rarego.in.dto;

import java.time.LocalDateTime;

public record RefundResponseDto(
	Long transactionId,
	Long auctionId,
	int refundAmount,
	String buyerId,
	int currentBalance,
	LocalDateTime createdAt) {
}
