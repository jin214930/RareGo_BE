package com.bugzero.rarego.domain;

import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;

import java.util.Map;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "AUCTION_OUTBOX")
public class AuctionOutbox extends BaseIdAndTime {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionOutboxType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionOutboxStatus status;

    @Column(nullable = false)
    private Long auctionId;

    @Column(nullable = false)
    private int retryCount;

    @Column(length = 1024)
    private String lastError;
    @Column(columnDefinition = "JSON", nullable = false)
    private String payload;

    public static AuctionOutbox createAuctionEnded(
            Long auctionId,
            Long bidderId,
            Integer bidAmount,
            Long productId
    ) {
        Map<String, Object> payload = Map.of(
                "bidderId", bidderId,
                "bidAmount", bidAmount,
                "productId", productId
        );

        return AuctionOutbox.builder()
                .auctionId(auctionId)
                .type(AuctionOutboxType.AUCTION_ENDED)
                .status(AuctionOutboxStatus.PENDING)
                .retryCount(0)
                .payload(toJson(payload))
                .build();
    }

    // Payload 파싱 헬퍼 메서드
    
    public Map<String, Object> getPayloadAsMap() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(this.payload, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse payload: " + this.payload, e);
        }
    }


    public <T> T getPayload(Class<T> clazz) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(this.payload, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse payload: " + this.payload, e);
        }
    }


    private static String toJson(Map<String, Object> payload) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert to JSON", e);
        }
    }

    // 상태 관리 메서드

    public void markSent() {
        this.status = AuctionOutboxStatus.SENT;
    }

    public void markFailed(String errorMessage, int maxRetry) {
        this.retryCount += 1;
        this.lastError = errorMessage;
        if (this.retryCount >= maxRetry) {
            this.status = AuctionOutboxStatus.FAILED;
        }
    }
}