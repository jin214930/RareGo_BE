package com.bugzero.rarego.product.in;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.product.app.ProductSearchService;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent; // [추가]
import com.bugzero.rarego.shared.auction.type.AuctionStatus; // [추가]

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductAuctionEventListener {

	private final ProductSearchService productSearchService;

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
}