package com.bugzero.rarego.global.outbox.app;

import static com.bugzero.rarego.global.response.ErrorType.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.kafka.KafkaTopics;
import com.bugzero.rarego.global.outbox.domain.OutboxEvent;
import com.bugzero.rarego.global.outbox.repository.OutboxEventRepository;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionRelistedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent;
import com.bugzero.rarego.shared.member.event.MemberJoinedEvent;
import com.bugzero.rarego.shared.member.event.MemberUpdatedEvent;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentCompletedEvent;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentExpiringSoonEvent;
import com.bugzero.rarego.shared.payment.event.PaymentTimeoutEvent;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;
import com.bugzero.rarego.shared.product.event.AuctionCreateEvent;
import com.bugzero.rarego.shared.product.event.AuctionDeleteEvent;
import com.bugzero.rarego.shared.product.event.AuctionUpdateEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxUseCase {

	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	@Transactional(propagation = Propagation.MANDATORY)
	public void saveOutbox(Object event) {
		OutboxEventMetadata metadata = resolveMetadata(event);

		if (metadata == null) {
			log.debug("해당 이벤트를 위한 아웃박스 메타데이터가 없습니다: {}", event.getClass().getSimpleName());
			throw new CustomException(UNSUPPORTED_OUTBOX_EVENT);
		}

		try {
			String payload = objectMapper.writeValueAsString(event);

			OutboxEvent outboxEvent = OutboxEvent.createOutboxEvent(
				metadata.aggregateType(),
				metadata.aggregateId(),
				event.getClass().getSimpleName(),
				metadata.topic(),
				payload
			);

			outboxEventRepository.save(outboxEvent);
			log.debug("Outbox에 다음 이벤트를 저장했습니다. 이벤트 : {}",
				metadata.aggregateType());

		} catch (JsonProcessingException e) {
			throw new CustomException(ErrorType.JSON_SERIALIZATION_FAILED);
		}
	}

	// 카프카로 보낼 이벤트 메타데이터 생성
	private OutboxEventMetadata resolveMetadata(Object event) {
		return switch (event) {
			case MemberJoinedEvent e -> new OutboxEventMetadata(
				"Member", String.valueOf(e.memberDto().id()),KafkaTopics.MEMBER_JOINED.getTopicName());
			case MemberUpdatedEvent e -> new OutboxEventMetadata(
				"Member", String.valueOf(e.memberDto().id()), KafkaTopics.MEMBER_UPDATE.getTopicName());
			case AuctionEndedEvent e -> new OutboxEventMetadata(
				"Auction", generateId(e.productId(), e.auctionId()), KafkaTopics.AUCTION_MANAGEMENT.getTopicName());
			case AuctionStartedEvent e -> new OutboxEventMetadata(
				"Auction", generateId(e.productId(), e.auctionId()), KafkaTopics.AUCTION_MANAGEMENT.getTopicName());
			case AuctionRelistedEvent e -> new OutboxEventMetadata(
				"Auction", generateId(e.productId(), e.newAuctionId()), KafkaTopics.AUCTION_MANAGEMENT.getTopicName());
			case SettlementFinishedEvent e -> new OutboxEventMetadata(
				"Payment", "settlement_payment", KafkaTopics.PAYMENT_SETTLEMENT_FINISHED.getTopicName());
			case AuctionPaymentCompletedEvent e -> new OutboxEventMetadata(
				"Payment", String.valueOf(e.sellerId()), KafkaTopics.PAYMENT_AUCTION_COMPLETED.getTopicName());
			case AuctionPaymentExpiringSoonEvent e -> new OutboxEventMetadata(
				"Payment", String.valueOf(e.buyerId()), KafkaTopics.PAYMENT_AUCTION_EXPIRING_SOON.getTopicName());
			case PaymentTimeoutEvent e -> new OutboxEventMetadata(
				"Payment", String.valueOf(e.auctionId()), KafkaTopics.PAYMENT_TIMEOUT.getTopicName());
			case AuctionCreateEvent e -> new OutboxEventMetadata(
				"Product", String.valueOf(e.productId()), KafkaTopics.AUCTION_INFO_MANAGEMENT.getTopicName());
			case AuctionUpdateEvent e -> new OutboxEventMetadata(
				"Product", String.valueOf(e.productId()), KafkaTopics.AUCTION_INFO_MANAGEMENT.getTopicName());
			case AuctionDeleteEvent e -> new OutboxEventMetadata(
				"Product", String.valueOf(e.productId()), KafkaTopics.AUCTION_INFO_MANAGEMENT.getTopicName());
			default -> null;
		};
	}

	private record OutboxEventMetadata(String aggregateType, String aggregateId,String topic) {
	}

	public String generateId(Long productId, Long auctionId) {
		return productId + "_" + auctionId;
	}
}
