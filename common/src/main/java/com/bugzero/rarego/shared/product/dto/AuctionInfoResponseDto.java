package com.bugzero.rarego.shared.product.dto;

import java.time.LocalDateTime;

import com.bugzero.rarego.shared.auction.type.AuctionStatus;

public record AuctionInfoResponseDto(
	Long productId,
	Long auctionId,
	int startPrice,
	int finalPrice,
	AuctionStatus auctionStatus,
	LocalDateTime startedAt,
	LocalDateTime closedAt
) {
}
