package com.bugzero.rarego.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Auction;
import com.bugzero.rarego.domain.AuctionOrder;
import com.bugzero.rarego.domain.Bid;
import com.bugzero.rarego.domain.event.AuctionFailedEvent;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.AuctionOrderRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.BidRepository;
import com.bugzero.rarego.out.es.ProductSearchClient;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.dto.ProductAuctionResponseDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionSettlementSupport {

	private final AuctionRepository auctionRepository;
	private final BidRepository bidRepository;
	private final AuctionOrderRepository auctionOrderRepository;
	private final OutboxUseCase outboxUseCase;
	private final ApplicationEventPublisher eventPublisher;
	private final ProductSearchClient productSearchClient;

	private static final int BATCH_SIZE = 100;

	@Transactional
	public List<Auction> findExpiredAuctions(LocalDateTime now) {
		return auctionRepository.findExpiredInProgressAuctionsWithLock(
			now, PageRequest.of(0, BATCH_SIZE)
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void processSettlement(Long auctionId) {
		Auction auction = auctionRepository.findByIdWithLock(auctionId)
			.orElseThrow(() -> new CustomException(ErrorType.AUCTION_NOT_FOUND));

		if (auction.getStatus() != AuctionStatus.IN_PROGRESS) {
			throw new CustomException(ErrorType.AUCTION_NOT_FOUND_OR_ALREADY_SETTLED);
		}

		if (auction.getEndTime().isAfter(LocalDateTime.now())) {
			throw new CustomException(ErrorType.AUCTION_NOT_FINISHED);
		}

		// 낙찰/유찰 처리
		if (bidRepository.existsByAuctionId(auction.getId())) {
			handleSuccess(auction);  // 외부 이벤트 → 아웃박스
		} else {
			handleFail(auction);     // 내부 이벤트 → 즉시 발행
		}
	}

	private void handleSuccess(Auction auction) {
		Bid winningBid = bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(auction.getId())
			.orElseThrow(() -> new CustomException(ErrorType.BID_NOT_FOUND));

		auction.end();
		auctionRepository.save(auction);

		auctionOrderRepository.save(
			AuctionOrder.builder()
				.auctionId(auction.getId())
				.sellerId(auction.getSellerId())
				.bidderId(winningBid.getBidderId())
				.finalPrice(winningBid.getBidAmount())
				.build()
		);

		String productName = getProductName(auction.getProductId());

		AuctionEndedEvent event = new AuctionEndedEvent(
			auction.getId(),
			winningBid.getBidderId(),
			winningBid.getBidAmount(),
			auction.getProductId(),
			productName
		);
		outboxUseCase.saveOutbox(event);

		log.info(
			"낙찰 정산 완료: auctionId={}, bidderId={}, bidAmount={}, productName={}",
			auction.getId(), winningBid.getBidderId(), winningBid.getBidAmount(), productName
		);
	}

	private void handleFail(Auction auction) {
		auction.end();
		auctionRepository.save(auction);

		String productName = getProductName(auction.getProductId());

		eventPublisher.publishEvent(
			new AuctionFailedEvent(
				auction.getId(),
				auction.getProductId(),
				productName
			)
		);

		log.info(
			"유찰 정산 완료: auctionId={}, productId={}, productName={}",
			auction.getId(), auction.getProductId(), productName
		);
	}

	private String getProductName(Long productId) {
		try {
			return productSearchClient.getProduct(productId)
				.map(ProductAuctionResponseDto::name)
				.orElse("Unknown Product");
		} catch (Exception e) {
			log.warn("상품명 조회 실패, 기본값 사용. productId={}", productId, e);
			return "Unknown Product";
		}
	}

	// ==================== 헬퍼 메서드 ====================

	@Transactional(readOnly = true)
	public boolean hasBids(Long auctionId) {
		return bidRepository.existsByAuctionId(auctionId);
	}

	@Transactional(readOnly = true)
	public Bid findWinningBid(Long auctionId) {
		return bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(auctionId)
			.orElseThrow(() -> new CustomException(ErrorType.BID_NOT_FOUND));
	}
}
