package com.bugzero.rarego.in;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Auction;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.AuctionBookmarkRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.es.ProductSearchClient;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionStartScheduler {

	private final AuctionRepository auctionRepository;
	private final AuctionBookmarkRepository auctionBookmarkRepository;
	private final OutboxUseCase outboxUseCase;
	private final ProductSearchClient productSearchClient;

	@Scheduled(cron = "0 * * * * *")
	@Transactional
	public void autoStartAuctions() {
		LocalDateTime now = LocalDateTime.now();

		List<Auction> pendingAuctions = auctionRepository.findAllByStatusAndStartTimeBefore(
			AuctionStatus.SCHEDULED, now
		);

		if (pendingAuctions.isEmpty()) {
			return;
		}

		log.info("경매 자동 시작 스케줄러 실행: {}건 시작 처리", pendingAuctions.size());

		for (Auction auction : pendingAuctions) {
			try {
				auction.start();

				List<Long> bookmarkedMemberIds = auctionBookmarkRepository
					.findMemberIdsByAuctionId(auction.getId());

				String productName = getProductName(auction.getProductId());

				AuctionStartedEvent event = new AuctionStartedEvent(
					auction.getId(),
					auction.getProductId(),
					auction.getStartTime(),
					productName,
					bookmarkedMemberIds
				);
				outboxUseCase.saveOutbox(event);

				log.info("경매 시작 아웃박스 저장 완료: auctionId={}, bookmarkedCount={}",
					auction.getId(), bookmarkedMemberIds.size());

			} catch (Exception e) {
				log.error("경매 ID {} 시작 처리 중 오류 발생", auction.getId(), e);
			}
		}
	}

	// ES 호출 실패가 경매 시작 트랜잭션 롤백으로 이어지지 않도록 예외를 삼킴
	private String getProductName(Long productId) {
		try {
			return productSearchClient.getProduct(productId)
				.map(product -> product.name())
				.orElse("Unknown Product");
		} catch (Exception e) {
			log.warn("상품명 조회 실패, 기본값 사용. productId={}", productId, e);
			return "Unknown Product";
		}
	}
}
