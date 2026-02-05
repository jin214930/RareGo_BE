package com.bugzero.rarego.product.app;

import com.bugzero.rarego.product.domain.document.ProductSearchDocument;
import com.bugzero.rarego.product.out.ProductSearchRepository;
import com.bugzero.rarego.shared.product.dto.ProductAuctionResponseDto;
import com.bugzero.rarego.shared.product.out.ProductApiClient;
import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.InspectionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductApiAdapter implements ProductApiClient {

	private final ProductSearchRepository searchRepository;
	private final ProductImageS3UseCase productImageS3UseCase;

	@Override
	public Optional<ProductAuctionResponseDto> getProduct(Long productId) {
		// ES에서 productId로 조회
		return searchRepository.findByProductId(productId)
			.map(this::convertToDto);
	}

	@Override
	public List<ProductAuctionResponseDto> getProducts(Set<Long> productIds) {
		if (productIds == null || productIds.isEmpty()) {
			return Collections.emptyList();
		}

		// ES 조회: List<String> id로 변환 필요 (ES _id가 productId.toString()인 경우)
		// 만약 productId 필드로 조회해야 한다면 findByProductIdIn 사용
		// 여기선 productId 필드 기준으로 조회한다고 가정
		List<ProductSearchDocument> docs = searchRepository.findByProductIdIn(productIds);

		return docs.stream()
			.map(this::convertToDto)
			.collect(Collectors.toList());
	}

	@Override
	public List<Long> getProductIdsBySellerId(Long sellerId) {
		return searchRepository.findAllBySellerId(sellerId).stream()
			.map(ProductSearchDocument::getProductId)
			.toList();
	}

	@Override
	public List<Long> searchProductIds(String keyword, Category category) {
		// ProductSearchService의 검색 로직을 활용하거나 Repository @Query 메서드 호출
		// 여기서는 Repository에 정의된 복합 검색 메서드를 활용하는 예시

		if (keyword != null && category != null) {
			// 키워드 + 카테고리
			return searchRepository.searchByKeywordAndCategory(keyword, category, null)
				.stream().map(ProductSearchDocument::getProductId).toList();
		} else if (category != null) {
			// 카테고리만
			return searchRepository.findAllByCategory(category).stream()
				.map(ProductSearchDocument::getProductId).toList();
		} else if (keyword != null) {
			// 키워드만
			return searchRepository.searchByKeyword(keyword, null)
				.stream().map(ProductSearchDocument::getProductId).toList();
		}

		return Collections.emptyList();
	}

	@Override
	public List<Long> getApprovedProductIds() {
		return searchRepository.findAllByInspectionStatus(InspectionStatus.APPROVED).stream()
			.map(ProductSearchDocument::getProductId)
			.toList();
	}

	// ✅ ES Document -> DTO 변환 (DB 조회 X)
	private ProductAuctionResponseDto convertToDto(ProductSearchDocument doc) {
		// 1. ES에 저장된 이미지 URL 리스트 가져오기
		List<String> imageUrls = doc.getImageUrls() != null ? doc.getImageUrls() : Collections.emptyList();

		// 2. Presigned URL 변환
		List<String> signedUrls = imageUrls.stream()
			.map(productImageS3UseCase::getPresignedGetUrl)
			.toList();

		String thumbnail = signedUrls.isEmpty() ? null : signedUrls.get(0);

		return ProductAuctionResponseDto.builder()
			.id(doc.getProductId())
			.sellerId(doc.getSellerId())
			.name(doc.getProductName())
			.startPrice(doc.getStartPrice())
			.category(doc.getCategory())
			.thumbnailUrl(thumbnail)
			.build();
	}
}