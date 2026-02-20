package com.bugzero.rarego.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.global.inbox.app.InboxUseCase;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.member.event.MemberJoinedEvent;
import com.bugzero.rarego.shared.member.event.MemberUpdatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
	topics = {"member-joined", "member-updated", "auction-ended"},
	groupId = "${spring.kafka.consumer.group-id}",
	containerFactory = "kafkaListenerContainerFactory"
)
public class PaymentConsumer {
	@Value("${spring.kafka.consumer.group-id}")
	private String consumerGroup;

	private final PaymentFacade paymentFacade;
	private final InboxUseCase inboxUseCase;

	@Transactional
	@KafkaHandler
	public void handleMemberJoined(@Payload MemberJoinedEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) {
			return;
		}

		try {
			paymentFacade.syncMember(event.memberDto());
			log.info("[payment] 회원 레플리카 등록 완료 - memberPublicId: {}", event.memberDto().publicId());
		} catch (Exception e) {
			log.error("[payment] 회원 레플리카 등록 실패 - memberPublicId: {}", event.memberDto().publicId(), e);
			throw e;
		}
	}

	@Transactional
	@KafkaHandler
	public void handleMemberUpdated(@Payload MemberUpdatedEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) {
			return;
		}

		try {
			paymentFacade.syncMember(event.memberDto());
			log.info("[payment] 회원 레플리카 수정 완료 - memberPublicId: {}", event.memberDto().publicId());
		} catch (Exception e) {
			log.error("[payment] 회원 레플리카 수정 실패 - memberPublicId: {}", event.memberDto().publicId(), e);
			throw e;
		}
	}

	// 경매 종료 이벤트 수신 → 보증금 반환 처리
	@Transactional
	@KafkaHandler
	public void handleAuctionEnded(@Payload AuctionEndedEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) {
			return;
		}

		try {
			log.info("카프카 메시지 수신: 경매 종료 - auctionId={}, winnerId={}",
				event.auctionId(), event.winnerId());

			paymentFacade.releaseDeposits(event.auctionId(), event.winnerId());

			log.info("보증금 반환 처리 완료 - auctionId={}", event.auctionId());

		} catch (Exception e) {
			log.error("보증금 반환 처리 실패 - auctionId: {}", event.auctionId(), e);
			// auto-offset-reset=earliest 설정으로 인해 재시작 시 재처리됨
			throw e;
		}
	}

	@KafkaHandler(isDefault = true)
	public void defaultHandler(Object object) {
		log.warn("[Auction] 수신된 이벤트 중 처리할 수 없는 타입입니다: {}", object.getClass().getName());
	}
}
