package bugzero.productservice.product.domain.dto;

import java.time.LocalDateTime;

import bugzero.productservice.shared.product.type.InspectionStatus;
import bugzero.productservice.shared.product.type.ProductCondition;
import lombok.Builder;

@Builder
public record ProductInspectionResponseDto(
	Long inspectionId,
	Long productId,
	InspectionStatus newStatus,
	ProductCondition productCondition,
	String reason,
	LocalDateTime updatedAt,
	LocalDateTime createdAt
) {
}
