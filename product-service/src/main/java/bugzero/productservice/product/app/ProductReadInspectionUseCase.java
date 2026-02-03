package bugzero.productservice.product.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bugzero.productservice.global.exception.CustomException;
import bugzero.productservice.global.response.ErrorType;
import bugzero.productservice.product.domain.Inspection;
import bugzero.productservice.product.domain.dto.ProductInspectionResponseDto;
import bugzero.productservice.product.out.InspectionRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductReadInspectionUseCase {
	private final InspectionRepository inspectionRepository;

	@Transactional(readOnly = true)
	public ProductInspectionResponseDto readInspection(Long productId) {
		Inspection inspection = inspectionRepository.findByProductId(productId)
			.orElseThrow(() -> new CustomException(ErrorType.INSPECTION_NOT_FOUND));

		return ProductInspectionResponseDto.builder()
			.inspectionId(inspection.getId())
			.productId(productId)
			.newStatus(inspection.getInspectionStatus())
			.productCondition(inspection.getProductCondition())
			.reason(inspection.getReason())
			.createdAt(inspection.getCreatedAt())
			.updatedAt(inspection.getUpdatedAt())
			.build();
	}
}
