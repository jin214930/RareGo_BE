package com.bugzero.rarego.in;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.member.event.MemberJoinedEvent;
import com.bugzero.rarego.shared.member.event.MemberUpdatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConsumer {
	private static final String GROUP_ID = "${spring.kafka.consumer.group-id}";

	private final PaymentFacade paymentFacade;

	@KafkaListener(topics = "member-joined", groupId = GROUP_ID)
	public void handleMemberJoined(MemberJoinedEvent event) {
		try {
			paymentFacade.syncMember(event.memberDto());
			log.info("[payment] 회원 레플리카 등록 완료 - memberPublicId: {}", event.memberDto().publicId());
		} catch (Exception e) {
			log.error("[payment] 회원 레플리카 등록 실패 - memberPublicId: {}", event.memberDto().publicId(), e);
			throw e;
		}
	}

	@KafkaListener(topics = "member-updated", groupId = GROUP_ID)
	public void handleMemberUpdated(MemberUpdatedEvent event) {
		try {
			paymentFacade.syncMember(event.memberDto());
			log.info("[payment] 회원 레플리카 수정 완료 - memberPublicId: {}", event.memberDto().publicId());
		} catch (Exception e) {
			log.error("[payment] 회원 레플리카 수정 실패 - memberPublicId: {}", event.memberDto().publicId(), e);
			throw e;
		}
	}

	// 경매 종료 이벤트 수신 → 보증금 반환 처리
	@KafkaListener(topics = "auction-ended", groupId = GROUP_ID)
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
			throw e;
		}
	}
}
