package com.bugzero.rarego.product.in;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.product.app.ProductSearchService;
import com.bugzero.rarego.product.app.ProductSupport;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionRelistedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductAuctionEventListener {

	private final ProductSearchService productSearchService;
	private final ProductSupport productSupport;

	// 경매 종료 처리
	@KafkaListener(topics = "auction-ended", groupId = "product-service-group")
	public void handleAuctionEnded(AuctionEndedEvent event) {
		log.info("Kafka Event Received: AuctionEnded for productId={}", event.productId());
		if (event.finalPrice() != null) {
			productSearchService.updateSoldPrice(event.productId(), event.finalPrice());
		}
	}

	// 경매 시작 처리
	@KafkaListener(topics = "auction-started", groupId = "product-service-group")
	public void handleAuctionStarted(AuctionStartedEvent event) {
		log.info("Kafka Event Received: AuctionStarted for productId={}", event.productId());

		// 상태를 진행 중으로 변경
		productSearchService.updateAuctionStatus(event.productId(), AuctionStatus.IN_PROGRESS);
	}

	// 기존 ES 문서를 찾아 경매 정보(ID, 가격, 시간)만 덮어씌움
	@KafkaListener(topics = "auction-relisted", groupId = "product-service-group")
	public void handleAuctionRelisted(AuctionRelistedEvent event) {
		log.info("Kafka Event Received [Auction Relisted]: productId={}, newAuctionId={}",
			event.productId(), event.newAuctionId());

		// 상품 원본 데이터 조회
		// 상품 자체 정보는 변하지 않았으므로 DB에서 가져오는 로직으로 구현.
		Product product = productSupport.findByIdWithImages(event.productId());

		// ES 업데이트
		// -> 문서 ID가 같으므로 기존 문서를 덮어씌워줌(Upsert).
		// -> 상태는 startedAt에 따라 자동으로 IN_PROGRESS 또는 SCHEDULED로 잡힘.
		productSearchService.save(
			product,
			product.getImages(),
			event.newAuctionId(),
			event.startPrice(),
			event.startedAt()
		);

		log.info("ES Updated for Relisted Auction: productId={}", event.productId());
	}
}