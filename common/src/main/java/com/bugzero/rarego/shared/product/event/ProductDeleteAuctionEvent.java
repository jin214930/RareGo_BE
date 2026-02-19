package com.bugzero.rarego.shared.product.event;

import lombok.Builder;

@Builder
public record ProductDeleteAuctionEvent(
	Long productId,
	String publicId
) {
}
