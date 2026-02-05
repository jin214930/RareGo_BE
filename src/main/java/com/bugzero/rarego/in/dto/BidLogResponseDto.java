package com.bugzero.rarego.in.dto;

import java.time.LocalDateTime;

import com.bugzero.rarego.domain.Bid;

public record BidLogResponseDto(
	Long id,
	String publicId,
	LocalDateTime bidTime,
	long bidAmount
) {
	public static BidLogResponseDto from(Bid bid, String publicId) {
		return new BidLogResponseDto(
			bid.getId(),
			publicId,
			bid.getBidTime(),
			bid.getBidAmount()
		);
	}
}
