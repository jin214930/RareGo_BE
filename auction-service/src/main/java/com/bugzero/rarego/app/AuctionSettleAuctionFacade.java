package com.bugzero.rarego.app;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuctionSettleAuctionFacade {

	private final AuctionSettleOneUseCase auctionSettleOneUseCase;

	/**
	 * 특정 경매 하나만 정산 (동적 스케줄링용)
	 */
	public void settleOne(Long auctionId) {
		auctionSettleOneUseCase.execute(auctionId);
	}
}
