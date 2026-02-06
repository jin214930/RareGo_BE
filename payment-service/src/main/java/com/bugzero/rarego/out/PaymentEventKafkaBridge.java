package com.bugzero.rarego.out;

import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bugzero.rarego.shared.payment.event.AuctionPaymentCompletedEvent;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventKafkaBridge {
	private final KafkaTemplate<String, Object> kafkaTemplate;

	private static final String TOPIC_SETTLEMENT_FINISHED = "payment-settlement-finished";
	private static final String TOPIC_AUCTION_PAYMENT_COMPLETED = "payment-auction-completed";

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void send(SettlementFinishedEvent event) {
		if (event.totalCount() == 0) {
			return;
		}

		log.info("Kafka 발행: 정산 완료 ( 총 {} 건, 금액 {}원)", event.totalCount(), event.totalAmount());

		String key = UUID.randomUUID().toString(); // 묶음 처리 건이므로 ID가 없으므로 UUID 이용

		kafkaTemplate.send(TOPIC_SETTLEMENT_FINISHED, key, event);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void send(AuctionPaymentCompletedEvent event) {
		log.info("Kafka 발행: 낙찰 결제 완료 (orderId={}, auctionId={})", event.orderId(), event.auctionId());

		// Key를 auctionId로 설정하여, 동일 경매 건에 대한 메시지 순서를 보장
		String key = String.valueOf(event.auctionId());

		kafkaTemplate.send(TOPIC_AUCTION_PAYMENT_COMPLETED, key, event);
	}
}
