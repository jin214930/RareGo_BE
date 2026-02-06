package com.bugzero.rarego.product.domain.event;

import com.bugzero.rarego.shared.product.type.InspectionStatus;
import com.bugzero.rarego.shared.product.type.ProductCondition;

public record ProductInspectionEvent(
	Long productId,
	InspectionStatus newStatus,
	ProductCondition newCondition
) {
}
