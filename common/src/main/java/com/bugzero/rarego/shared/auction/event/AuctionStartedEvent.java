package com.bugzero.rarego.shared.auction.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 경매 시작 시 발행되는 이벤트
 *
 * @param auctionId           경매 ID
 * @param productId           상품 ID
 * @param startedAt           경매 시작 시간
 * @param productName         상품명
 * @param bookmarkedMemberIds 북마크한 회원 ID 리스트
 */
public record AuctionStartedEvent(
	Long auctionId,
	Long productId,
	LocalDateTime startedAt,
	String productName,
	List<Long> bookmarkedMemberIds
) {
}