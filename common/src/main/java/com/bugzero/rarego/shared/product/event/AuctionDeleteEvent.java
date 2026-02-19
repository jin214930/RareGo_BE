package com.bugzero.rarego.shared.product.event;

import lombok.Builder;

@Builder
public record AuctionDeleteEvent(
	Long productId,
	String publicId
) {
}
