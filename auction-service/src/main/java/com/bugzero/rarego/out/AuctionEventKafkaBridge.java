package com.bugzero.rarego.out;

import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AuctionEndedEvent를 Kafka로 중계하는 브릿지
 * DB 커밋 후에만 전송하여 데이터 일관성 보장
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionEventKafkaBridge {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TOPIC_AUCTION_ENDED = "auction-ended";

    /**
     * TransactionPhase.AFTER_COMMIT
     * - AuctionSettlementSupport의 트랜잭션이 성공적으로 커밋된 직후에 실행
     * - DB는 롤백되었는데 Kafka 메시지만 날아가는 '유령 메시지' 현상 방지
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendAuctionEndedToKafka(AuctionEndedEvent event) {
        log.info("Bridge: Kafka로 경매 종료 이벤트 전송 [Topic: {}, AuctionId: {}]",
                TOPIC_AUCTION_ENDED, event.auctionId());

        // 메시지 순서 보장을 위해 Key를 auctionId로 설정
        String key = String.valueOf(event.auctionId());

        kafkaTemplate.send(TOPIC_AUCTION_ENDED, key, event);
    }
}