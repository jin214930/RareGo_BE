package com.bugzero.rarego.app.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bugzero.rarego.app.NotificationSupport;
import com.bugzero.rarego.domain.Notification;
import com.bugzero.rarego.domain.NotificationMember;
import com.bugzero.rarego.domain.NotificationType;
import com.bugzero.rarego.shared.auction.event.AuctionOutbidEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuctionOutbidMapper implements NotificationMapper<AuctionOutbidEvent> {
	private final NotificationSupport notificationSupport;

	@Override
	public boolean supports(Object event) {
		return event instanceof AuctionOutbidEvent;
	}

	@Override
	public List<Notification> map(AuctionOutbidEvent event) {
		String message = "누군가 '%s'의 입찰가를 갱신했습니다. (현재가: %d원)"
			.formatted(event.productName(), event.currentPrice());

		NotificationMember member = notificationSupport.findMemberById(event.memberId());

		Notification notification = Notification.builder()
			.message(message)
			.member(member)
			.type(NotificationType.AUCTION_OUTBID)
			.referenceId(event.auctionId())
			.build();

		return List.of(notification);
	}
}
