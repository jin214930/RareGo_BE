package com.bugzero.rarego.ai.domain.dto;

import com.bugzero.rarego.ai.domain.type.TemporaryCondition;
import com.bugzero.rarego.shared.product.type.Category;

public record AiExternalPriceRequestDto(
	Category category,
	TemporaryCondition condition,
	String name,
	String description
) {
}
