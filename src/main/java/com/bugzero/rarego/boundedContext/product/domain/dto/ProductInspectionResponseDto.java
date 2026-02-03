package com.bugzero.rarego.boundedContext.product.domain.dto;

import java.time.LocalDateTime;

import com.bugzero.rarego.shared.product.type.InspectionStatus;
import com.bugzero.rarego.shared.product.type.ProductCondition;

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
