package com.bugzero.rarego.boundedContext.product.domain.dto;

import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.InspectionStatus;

public record ProductSearchForInspectionCondition(
	String name,
	Category category,
	InspectionStatus status
) {
}
