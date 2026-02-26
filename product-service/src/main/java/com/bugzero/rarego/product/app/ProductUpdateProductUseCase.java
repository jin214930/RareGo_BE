package com.bugzero.rarego.product.app;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.event.EventPublisher;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.product.domain.dto.ProductUpdateResponseDto;
import com.bugzero.rarego.shared.product.dto.ProductAuctionUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductImageUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductUpdateDto;
import com.bugzero.rarego.shared.product.event.ProductUpdateAuctionEvent;
import com.bugzero.rarego.shared.product.event.S3ImageConfirmEvent;
import com.bugzero.rarego.shared.product.event.S3ImageDeleteEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductUpdateProductUseCase {
	private final ProductSupport productSupport;
	private final OutboxUseCase outboxUseCase;
	private final EventPublisher eventPublisher;
	private final ProductSearchService productSearchService;

	@Transactional
	public ProductUpdateResponseDto updateProduct(String  publicId, Long productId, ProductUpdateDto dto) {
		//유효한 멤버인지 확인
		ProductMember seller = productSupport.verifyValidateMember(publicId);
		//유효한 상품인지 확인
		Product product = productSupport.verifyValidateProduct(productId);
		//수정 가능한 상품인지 확인
		productSupport.isAbleToChange(seller, product);
		//상품 이미지 순서 보장 정렬
		List<ProductImageUpdateDto> images = productSupport.normalizeUpdateImageOrder(
			dto.productImageUpdateDtos()
		);
		//상품 기본 정보 수정
		product.updateBasicInfo(
			dto.name(),
			dto.category(),
			dto.description()
		);
		//수정 중 삭제되는 이미지가 있다면 반환
		List<String> pathToDelete = product.removeOldImages(images);
		//수정 중 새롭게 등록되는 이미지가 있다면 반환
		List<String> pathToUpdate = product.processNewImages(images);
		//S3삭제 이벤트 발행
		eventPublisher.publish(new S3ImageDeleteEvent(pathToDelete));
		//S3등록 이벤트 발행
		eventPublisher.publish(new S3ImageConfirmEvent(pathToUpdate));

		//아웃박스 이벤트 저장
		outboxUseCase.saveOutbox(ProductUpdateAuctionEvent.builder()
			.productId(productId)
			.publicId(publicId)
			.dto(dto.productAuctionUpdateDto())
			.build());

		synchronizeElasticsearch(product, dto.productAuctionUpdateDto());

		return ProductUpdateResponseDto.builder()
			.productId(productId)
			.build();
	}

	private void synchronizeElasticsearch(Product product, ProductAuctionUpdateDto dto) {
		try {
			productSearchService.updatedBeforeInspection(
				product,
				product.getImages(),
				dto.startPrice(),
				dto.durationDays()

			);
		} catch (Exception e) {
			// 예외를 catch하고 다시 throw하지 않음
			log.error("ES 동기화 실패 (DB는 정상 커밋됨). 추후 배치로 복구 필요: productId={}, error={}",
				product.getId(), e.getMessage());
		}
	}
}
