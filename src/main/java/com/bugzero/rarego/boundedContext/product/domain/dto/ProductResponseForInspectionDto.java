package com.bugzero.rarego.boundedContext.product.domain.dto;

import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.InspectionStatus;

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
