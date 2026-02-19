package com.bugzero.rarego.shared.product.event;

import com.bugzero.rarego.shared.product.dto.ProductAuctionUpdateDto;

import lombok.Builder;

@Builder
public record AuctionUpdateEvent(
	Long productId,
	String publicId,
	ProductAuctionUpdateDto dto
) {
}
