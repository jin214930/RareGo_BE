package bugzero.productservice.product.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bugzero.productservice.global.response.PagedResponseDto;
import bugzero.productservice.product.domain.dto.ProductResponseForInspectionDto;
import bugzero.productservice.product.domain.dto.ProductSearchForInspectionCondition;
import bugzero.productservice.product.out.ProductRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductReadProductsForInspectionUseCase {
	private final ProductRepository productRepository;
	private final ProductImageS3UseCase s3PresignerUrlUseCase;

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
			s3PresignerUrlUseCase.getPresignedGetUrl(dto.thumbnail())
		);
	}
}
