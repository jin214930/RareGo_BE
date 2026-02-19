package com.bugzero.rarego.shared.product.dto;

import java.time.LocalDateTime;

public record AuctionInfoResponseDto(Long productId, Long auctionId, int startPrice, LocalDateTime startedAt) {
}
