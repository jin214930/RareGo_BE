package com.bugzero.rarego.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.app.AuctionFacade;
import com.bugzero.rarego.global.inbox.app.InboxUseCase;
import com.bugzero.rarego.shared.member.event.MemberJoinedEvent;
import com.bugzero.rarego.shared.member.event.MemberUpdatedEvent;
import com.bugzero.rarego.shared.product.event.ProductCreateAuctionEvent;
import com.bugzero.rarego.shared.product.event.ProductDeleteAuctionEvent;
import com.bugzero.rarego.shared.product.event.ProductUpdateAuctionEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
	topics = {"auction-info-management", "member-joined", "member-updated"},
	groupId = "${spring.kafka.consumer.group-id}",
	containerFactory = "kafkaListenerContainerFactory"
)
public class AuctionConsumer {
	@Value("${spring.kafka.consumer.group-id}")
	private String consumerGroup;

	private final AuctionFacade auctionFacade;
	private final InboxUseCase inboxUseCase;

	/* --- 상품/경매 관련 이벤트 핸들러 --- */

	@Transactional
	@KafkaHandler
	public void onAuctionEvent(@Payload ProductCreateAuctionEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) return;

		log.info("[Auction] 상품 경매 생성 수신: ProductId={}, messageId={}", event.productId(), messageId);
		auctionFacade.createAuction(event.productId(), event.publicId(), event.dto());
	}

	@Transactional
	@KafkaHandler
	public void onAuctionEvent(@Payload ProductUpdateAuctionEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) return;

		log.info("[Auction] 상품 경매 수정 수신: ProductId={}, messageId={}", event.productId(), messageId);
		auctionFacade.updateAuction(event.publicId(), event.dto());
	}

	@Transactional
	@KafkaHandler
	public void onAuctionEvent(@Payload ProductDeleteAuctionEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) return;

		log.info("[Auction] 상품 경매 삭제 수신: ProductId={}, messageId={}", event.productId(), messageId);
		auctionFacade.deleteAuction(event.publicId(), event.productId());
	}

	/* --- 회원 관련 이벤트 핸들러 (통합됨) --- */

	@Transactional
	@KafkaHandler
	public void onMemberEvent(@Payload MemberJoinedEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) return;

		log.info("[Auction] 회원 가입 수신: MemberId={}, messageId={}", event.memberDto().id(), messageId);
		auctionFacade.syncMember(event.memberDto());
	}

	@Transactional
	@KafkaHandler
	public void onMemberEvent(@Payload MemberUpdatedEvent event, @Header("messageId") String messageId) {
		if (inboxUseCase.isAlreadyProcessed(messageId, consumerGroup)) return;

		log.info("[Auction] 회원 정보 수정 수신: MemberId={}, messageId={}", event.memberDto().id(), messageId);
		auctionFacade.syncMember(event.memberDto());
	}

	@KafkaHandler(isDefault = true)
	public void defaultHandler(Object object) {
		log.warn("[Auction] 수신된 이벤트 중 처리할 수 없는 타입입니다: {}", object.getClass().getName());
	}
}
