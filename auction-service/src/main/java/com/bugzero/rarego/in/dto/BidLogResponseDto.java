package com.bugzero.rarego.in.dto;

import java.time.LocalDateTime;

import com.bugzero.rarego.domain.Bid;

public record BidLogResponseDto(
	Long id,
	String publicId,
	String nickname,
	LocalDateTime bidTime,
	long bidAmount
) {
	public static BidLogResponseDto from(Bid bid, String publicId, String nickname) {
		return new BidLogResponseDto(
			bid.getId(),
			publicId,
			nickname,
			bid.getBidTime(),
			bid.getBidAmount()
		);
	}
}
