package com.bugzero.rarego.shared.auction.event;

public record AuctionOutbidEvent(
	Long bidId,
	Long auctionId,
	String productName,
	Long bidderId,        // 입찰자 id (추월한 사람)
	int currentPrice,     // 갱신된 가격
	Long memberId         // 이전 최고 입찰자 id (수신 대상)
) {
}
