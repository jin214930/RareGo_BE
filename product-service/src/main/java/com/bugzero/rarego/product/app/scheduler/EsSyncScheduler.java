package com.bugzero.rarego.product.app.scheduler;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.product.app.ProductSearchService;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.out.ProductRepository;
import com.bugzero.rarego.product.out.ProductSearchRepository;
import com.bugzero.rarego.shared.auction.out.AuctionApiClient;
import com.bugzero.rarego.shared.product.dto.AuctionInfoResponseDto;
import com.bugzero.rarego.shared.product.type.InspectionStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EsSyncScheduler {

	private final ProductRepository productRepository;
	private final ProductSearchRepository productSearchRepository;
	private final ProductSearchService productSearchService;
	private final AuctionApiClient auctionApiClient;

	// 배치 주기: 매 시간 정각 (0분 0초) 실행
	@Scheduled(cron = "0 0 * * * *")
	@Transactional(readOnly = true)
	public void syncMissingProducts() {
		log.info(">>>> [ES Sync Batch] 데이터 정합성 검사 시작");

		// 조회 범위 설정 (효율성 위해 지난 2시간 동안 업데이트된 상품)
		LocalDateTime targetTime = LocalDateTime.now().minusHours(2);

		List<Product> recentProducts = productRepository.findAllByInspectionStatusAndUpdatedAtAfter(
			InspectionStatus.APPROVED,
			targetTime
		);

		int checkedCount = 0;
		int fixedCount = 0;

		for (Product product : recentProducts) {
			checkedCount++;
			try {
				// ES에 존재하는지 확인(최대한 경량 체크)
				boolean existsInEs = productSearchRepository.existsById(product.getId().toString());

				if (!existsInEs) {
					log.warn("[ES Sync Batch] 누락 데이터 발견! 복구 시도: productId={}", product.getId());

					// 재적재 수행
					reindexProduct(product);
					fixedCount++;
				}

			} catch (Exception e) {
				log.error("[ES Sync Batch] 개별 상품 처리 중 오류: productId={}", product.getId(), e);
			}
		}

		log.info(">>>> [ES Sync Batch] 종료. 검사: {}, 복구: {}", checkedCount, fixedCount);
	}

	/**
	 * 단건 재적재 로직
	 */
	private void reindexProduct(Product product) {
		// 경매 정보 조회
		AuctionInfoResponseDto auctionInfo = auctionApiClient.getAuctionInfo(product.getId());

		// ES 저장
		productSearchService.save(product, product.getImages(), auctionInfo);
	}
}
