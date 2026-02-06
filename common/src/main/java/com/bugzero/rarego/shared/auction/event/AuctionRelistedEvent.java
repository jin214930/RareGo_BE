package com.bugzero.rarego.shared.auction.event;

import java.time.LocalDateTime;

public record AuctionRelistedEvent(
	Long productId,      // 상품 ID
	Long newAuctionId,   // 새로 생성된 경매 ID
	int startPrice,      // 새 시작 가격
	LocalDateTime startedAt // 새 시작 시간
) {
}
