package com.bugzero.rarego.shared.payment.dto;

import java.time.LocalDateTime;

public record DepositHoldResponseDto(
        Long depositId,
        Long auctionId,
        int amount,
        String status,
        LocalDateTime createdAt) {
    public static DepositHoldResponseDto from(
            Long depositId,
            Long auctionId,
            int amount,
            String status,
            LocalDateTime createdAt) {
        return new DepositHoldResponseDto(depositId, auctionId, amount, status, createdAt);
    }
}
