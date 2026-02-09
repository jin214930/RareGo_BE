package com.bugzero.rarego.app;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.global.response.PagedResponseDto;
import com.bugzero.rarego.in.dto.NotificationResponse;
import com.bugzero.rarego.in.dto.NotificationUnreadCountResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationFacade {
	private final NotificationCreateNotificationUseCase notificationCreateNotificationUseCase;
	private final NotificationGetNotificationsUseCase notificationGetNotificationsUseCase;
	private final NotificationGetUnreadCountUseCase notificationGetUnreadCountUseCase;
	private final NotificationMarkAsReadUseCase notificationMarkAsReadUseCase;

	public void createNotification(Object event) {
		notificationCreateNotificationUseCase.createNotification(event);
	}

	public PagedResponseDto<NotificationResponse> getNotifications(String publicId, Boolean onlyUnread,
		Pageable pageable) {
		return notificationGetNotificationsUseCase.getNotifications(publicId, onlyUnread, pageable);
	}

	public NotificationUnreadCountResponse getUnreadCount(String publicId) {
		return notificationGetUnreadCountUseCase.getUnreadCount(publicId);
	}

	public void markAsRead(String publicId, Long id) {
		notificationMarkAsReadUseCase.markAsRead(publicId, id);
	}
}
