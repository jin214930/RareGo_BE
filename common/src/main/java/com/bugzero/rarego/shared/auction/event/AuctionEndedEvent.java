package com.bugzero.rarego.shared.auction.event;

import java.util.List;

/**
 * 경매 종료 시 발행되는 이벤트
 *
 * @param auctionId           경매 ID
 * @param winnerId            낙찰자 ID (유찰인 경우 null)
 * @param finalPrice          최종 낙찰가 (유찰인 경우 null)
 * @param productId           상품 ID
 * @param productName         상품명
 * @param bookmarkedMemberIds 북마크한 회원 ID 리스트
 */
public record AuctionEndedEvent(
	Long auctionId,
	Long winnerId,
	Integer finalPrice,
	Long productId,
	String productName,
	List<Long> bookmarkedMemberIds
) {
}
