package com.bugzero.rarego.app.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bugzero.rarego.app.NotificationSupport;
import com.bugzero.rarego.domain.Notification;
import com.bugzero.rarego.domain.NotificationMember;
import com.bugzero.rarego.domain.NotificationType;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuctionStartedMapper implements NotificationMapper<AuctionStartedEvent> {
	private final NotificationSupport notificationSupport;

	@Override
	public boolean supports(Object event) {
		return event instanceof AuctionStartedEvent;
	}

	@Override
	public List<Notification> map(AuctionStartedEvent event) {
		if (event.bookmarkedMemberIds() == null || event.bookmarkedMemberIds().isEmpty()) {
			return List.of();
		}

		return event.bookmarkedMemberIds().stream()
			.map(memberId -> createNotification(memberId, event))
			.toList();
	}

	private Notification createNotification(Long memberId, AuctionStartedEvent event) {
		String message = "관심 상품 '%s'의 경매가 시작되었습니다.".formatted(event.productName());
		NotificationMember member = notificationSupport.findMemberById(memberId);

		return Notification.builder()
			.message(message)
			.member(member)
			.type(NotificationType.BOOKMARK_AUCTION_STARTED)
			.referenceId(event.auctionId())
			.build();
	}
}
