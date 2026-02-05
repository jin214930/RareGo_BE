package com.bugzero.rarego.product.app;

import static com.bugzero.rarego.global.config.GlobalConfig.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.event.EventPublisher;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.product.domain.Inspection;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.product.domain.dto.ProductInspectionRequestDto;
import com.bugzero.rarego.product.domain.dto.ProductInspectionResponseDto;
import com.bugzero.rarego.product.domain.event.ProductInspectionEvent;
import com.bugzero.rarego.product.out.InspectionRepository;
import com.bugzero.rarego.shared.product.dto.AuctionInfoResponseDto;
import com.bugzero.rarego.shared.product.type.InspectionStatus;
import com.bugzero.rarego.shared.product.type.ProductCondition;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductCreateInspectionUseCase {
	private final InspectionRepository inspectionRepository;
	private final ProductSupport productSupport;

	@Transactional
	public ProductInspectionResponseDto createInspection(String inspectorId, ProductInspectionRequestDto dto) {
		//반려 시 이유가 있는지 확인
		checkedReason(dto);
		//유효한 상품인지 확인
		Product product = productSupport.verifyValidateProduct(dto.productId());
		//유효한 판매자인지 확인 (탈퇴한 회원이거나 데이터가 없으면 예외처리)
		ProductMember seller = product.getSeller();
		if (seller.isDeleted()) {
			throw new CustomException(ErrorType.MEMBER_NOT_FOUND);
		}
		//이미 검수가 끝난 상품인지 확인
		checkedProductStatus(product);
		//유효한 관리자인지 확인
		ProductMember admin = productSupport.verifyValidateMember(inspectorId);

		Inspection inspection = inspectionRepository.save(dto.toEntity(product, seller, admin.getId()));

		//상품데이터의 검수 상태도 동기화
		product.determineInspection(dto.status());
		//상품데이터의 상품상태도 동기화
		product.determineProductCondition(dto.productCondition());

		return ProductInspectionResponseDto.builder()
			.inspectionId(inspection.getId())
			.productId(inspection.getProduct().getId())
			.newStatus(inspection.getInspectionStatus())
			.productCondition(inspection.getProductCondition())
			.reason(inspection.getReason())
			.createdAt(inspection.getCreatedAt())
			.updatedAt(inspection.getUpdatedAt())
			.build();
	}

	private void synchronizeElasticsearch(Product product, InspectionStatus status) {
		try {
			if (status == InspectionStatus.APPROVED) {
				// 승인됨 -> 경매 정보 조회(API) -> ES 적재
				AuctionInfoResponseDto auctionInfo = auctionApiClient.getAuctionInfo(product.getId());

				productSearchService.save(
					product,
					product.getImages(),
					auctionInfo.auctionId(),
					auctionInfo.startPrice(),
					auctionInfo.startedAt()
				);
				log.info("검수 승인 및 ES 적재 완료 (Sync): productId={}", product.getId());
			} else {
				// 반려/삭제 등 -> ES에서 제거
				productSearchService.delete(product.getId());
				log.info("검수 반려/삭제로 인한 ES 제거 (Sync): productId={}", product.getId());
			}
		} catch (Exception e) {
			log.error("ES 동기화 실패 (Transaction Rollback): productId={}", product.getId(), e);
			// ★ 중요: 여기서 예외를 던져야 DB 트랜잭션도 같이 롤백됩니다.
			// 만약 ES 실패해도 DB는 저장하고 싶다면 catch만 하고 throw를 안 하면 됩니다.
			// 하지만 '데이터 정합성'이 중요하다면 throw 하는 것이 맞습니다.
			throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
		}
	}

	private void checkedReason(ProductInspectionRequestDto dto) {
		if (dto.status() == InspectionStatus.REJECTED && dto.reason() == null) {
			throw new CustomException(ErrorType.INSPECTION_REJECT_REASON_REQUIRED);
		}
	}

	private void checkedProductStatus(Product product) {
		// 검수 상태가 대기중이 아니거나 상품 상태가 검수 예정 중인 경우가 아니라면 검수가 이미 완료된 것이기 때문에 예외 발생
		if (product.getInspectionStatus() != InspectionStatus.PENDING
			|| product.getProductCondition() != ProductCondition.INSPECTION) {
			throw new CustomException(ErrorType.INSPECTION_ALREADY_COMPLETED);
		}
	}


}
