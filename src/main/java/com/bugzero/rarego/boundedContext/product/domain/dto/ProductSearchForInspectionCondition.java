package com.bugzero.rarego.boundedContext.product.domain.dto;

import com.bugzero.rarego.boundedContext.product.domain.Category;
import com.bugzero.rarego.boundedContext.product.domain.InspectionStatus;

public record ProductSearchForInspectionCondition(
	String name,
	Category category,
	InspectionStatus status
) {
}
