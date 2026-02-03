package bugzero.productservice.product.domain.dto;

import bugzero.productservice.shared.product.type.Category;
import bugzero.productservice.shared.product.type.InspectionStatus;
import lombok.Builder;

@Builder
public record ProductResponseForInspectionDto(
	Long ProductId,
	String name,
	String sellerEmail,
	Category category,
	InspectionStatus inspectionStatus,
	String thumbnail
) {
}
