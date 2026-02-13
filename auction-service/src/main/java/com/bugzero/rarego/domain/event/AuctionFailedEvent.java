package com.bugzero.rarego.domain.event;

import java.util.List;

/**
 * 경매 실패(유찰) 시 발행되는 이벤트
 *
 * @param auctionId           경매 ID
 * @param productId           상품 ID
 * @param productName         상품명
 * @param bookmarkedMemberIds 북마크한 회원 ID 리스트
 */
public record AuctionFailedEvent(
	Long auctionId,
	Long productId,
	String productName,
	List<Long> bookmarkedMemberIds
) {
}
