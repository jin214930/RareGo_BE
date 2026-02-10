package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.shared.member.domain.MemberDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationFacade {
	private final NotificationCreateNotificationUseCase notificationCreateNotificationUseCase;
	private final NotificationSyncMemberUseCase notificationSyncMemberUseCase;

	public void createNotification(Object event) {
		notificationCreateNotificationUseCase.createNotification(event);
	}

	public void syncMember(MemberDto memberDto) {
		notificationSyncMemberUseCase.syncMember(memberDto);
	}
}
