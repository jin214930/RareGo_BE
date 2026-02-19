package com.bugzero.rarego.shared.product.event;

import com.bugzero.rarego.shared.product.dto.ProductAuctionCreateDto;

import lombok.Builder;

@Builder
public record ProductCreateAuctionEvent(
	Long productId,
	String publicId,
	ProductAuctionCreateDto dto
) {
}
