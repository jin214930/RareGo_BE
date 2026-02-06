package com.bugzero.rarego.out;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auction 모듈에서 발행한 경매 종료 이벤트를 수신하여 처리
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionEventListener {

    private final PaymentFacade paymentFacade;

    /**
     * 경매 종료 이벤트 수신 → 보증금 반환 처리
     * groupId는 application.yml에서 자동 설정 (payment-service-group)
     */
    // TODO: @Retryable 대신 kafka errorhandler + DLQ 패턴 도입 고민
    @KafkaListener(topics = "auction-ended", groupId = "payment-service-group")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAuctionEnded(AuctionEndedEvent event) {
        try {
            log.info("카프카 메시지 수신: 경매 종료 - auctionId={}, winnerId={}",
                    event.auctionId(), event.winnerId());

            paymentFacade.releaseDeposits(event.auctionId(), event.winnerId());

            log.info("보증금 반환 처리 완료 - auctionId={}", event.auctionId());

        } catch (Exception e) {
            log.error("보증금 반환 처리 실패 - auctionId: {}", event.auctionId(), e);
            // auto-offset-reset=earliest 설정으로 인해 재시작 시 재처리됨
            throw e; // 재시도를 위해 예외를 던짐
        }
    }
}