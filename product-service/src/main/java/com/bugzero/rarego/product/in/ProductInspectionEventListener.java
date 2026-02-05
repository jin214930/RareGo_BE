package com.bugzero.rarego.product.in;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bugzero.rarego.product.app.ProductSearchService;
import com.bugzero.rarego.product.app.ProductSupport;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.event.ProductInspectionEvent;
import com.bugzero.rarego.shared.auction.out.AuctionApiClient;
import com.bugzero.rarego.shared.product.dto.AuctionInfoResponseDto;
import com.bugzero.rarego.shared.product.type.InspectionStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductInspectionEventListener {

	private final ProductSearchService productSearchService;
	private final AuctionApiClient auctionApiClient;
	private final ProductSupport productSupport;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleInspectionEvent(ProductInspectionEvent event) {
		log.info("검수 이벤트 수신: productId={}, status={}", event.productId(), event.newStatus());

		try {
			if (event.newStatus() == InspectionStatus.APPROVED) {
				// ES 적재
				indexProductToEs(event.productId());
			} else {
				// ES에서 제거 (혹시 존재할 수 있으므로)
				productSearchService.delete(event.productId());
			}
		} catch (Exception e) {
			log.error("ES 동기화 실패: productId={}, error={}", event.productId(), e.getMessage());
		}
	}

	private void indexProductToEs(Long productId) {
		Product product = productSupport.findByIdWithImages(productId);
		AuctionInfoResponseDto auctionInfo = auctionApiClient.getAuctionInfo(productId);

		productSearchService.save(
			product,
			product.getImages(),
			auctionInfo.auctionId(),
			auctionInfo.startPrice(),
			auctionInfo.startedAt()
		);
		log.info("ES 초기 적재 완료: productId={}", productId);
	}
}