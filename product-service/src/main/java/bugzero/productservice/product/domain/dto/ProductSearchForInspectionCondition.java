package bugzero.productservice.product.domain.dto;

import bugzero.productservice.shared.product.type.Category;
import bugzero.productservice.shared.product.type.InspectionStatus;

public record ProductSearchForInspectionCondition(
	String name,
	Category category,
	InspectionStatus status
) {
}
