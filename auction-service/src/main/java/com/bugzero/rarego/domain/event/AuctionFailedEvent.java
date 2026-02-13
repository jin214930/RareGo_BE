package com.bugzero.rarego.domain.event;

/**
 * 경매 실패(유찰) 시 발행되는 이벤트
 *
 * @param auctionId   경매 ID
 * @param productId   상품 ID
 * @param productName 상품명
 */
public record AuctionFailedEvent(
	Long auctionId,
	Long productId,
	String productName
) {
}
