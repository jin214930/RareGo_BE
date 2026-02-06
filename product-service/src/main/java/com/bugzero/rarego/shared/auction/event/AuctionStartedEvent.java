package com.bugzero.rarego.shared.auction.event;

import java.time.LocalDateTime;

/**
 * 경매 시작 시 발행되는 이벤트
 *
 * @param auctionId  경매 ID
 * @param productId  상품 ID
 * @param startedAt  경매 시작 시간
 */
public record AuctionStartedEvent(
	Long auctionId,
	Long productId,
	LocalDateTime startedAt
) {
}