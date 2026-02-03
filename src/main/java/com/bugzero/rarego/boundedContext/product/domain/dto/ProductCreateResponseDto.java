package com.bugzero.rarego.boundedContext.product.domain.dto;

import com.bugzero.rarego.shared.product.type.InspectionStatus;

import lombok.Builder;

@Builder
public record ProductCreateResponseDto(
	long productId,
	long auctionId,
	InspectionStatus inspectionStatus
) {
}
