package com.bugzero.rarego.bounded_context.payment.domain;

import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "PAYMENT_OUTBOX")
public class PaymentOutbox extends BaseIdAndTime {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentOutboxType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentOutboxStatus status;

    @Column(nullable = false)
    private Long auctionId;

    @Column(nullable = false)
    private int retryCount;

    @Column(length = 1024)
    private String lastError;

    public static PaymentOutbox forAuctionFail(Long auctionId) {
        return PaymentOutbox.builder()
                .type(PaymentOutboxType.AUCTION_ORDER_FAIL)
                .status(PaymentOutboxStatus.PENDING)
                .auctionId(auctionId)
                .retryCount(0)
                .build();
    }

    public void markSent() {
        this.status = PaymentOutboxStatus.SENT;
    }

    public void markFailed(String errorMessage, int maxRetry) {
        this.retryCount += 1;
        this.lastError = errorMessage;
        if (this.retryCount >= maxRetry) {
            this.status = PaymentOutboxStatus.FAILED;
        }
    }
}

