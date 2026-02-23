package com.bugzero.rarego.product.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.response.PagedResponseDto;
import com.bugzero.rarego.global.util.S3Utils;
import com.bugzero.rarego.product.domain.dto.ProductResponseForInspectionDto;
import com.bugzero.rarego.product.domain.dto.ProductSearchForInspectionCondition;
import com.bugzero.rarego.product.out.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductReadProductsForInspectionUseCase {
	private final ProductRepository productRepository;
	private final S3Utils s3Utils;

	@Transactional(readOnly = true)
	public PagedResponseDto<ProductResponseForInspectionDto> readProducts(
		ProductSearchForInspectionCondition condition, Pageable pageable
	) {
		Page<ProductResponseForInspectionDto> productDtos = productRepository.
			readProductsForAdmin(condition.name(), condition.category(), condition.status(), pageable);

		return PagedResponseDto.from(productDtos, this::toPresignedDto);
	}

	private ProductResponseForInspectionDto toPresignedDto(ProductResponseForInspectionDto dto) {
		return new ProductResponseForInspectionDto(
			dto.ProductId(),
			dto.name(),
			dto.sellerEmail(),
			dto.category(),
			dto.inspectionStatus(),
			s3Utils.getPublicUrl(dto.thumbnail())
		);
	}
}
