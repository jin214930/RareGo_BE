package com.bugzero.rarego.in.dto;

import java.time.LocalDateTime;

import com.bugzero.rarego.domain.Notification;

public record NotificationResponse(
	Long id,
	String title,
	String message,
	String link,
	boolean isRead,
	String type,
	LocalDateTime createdAt
) {
	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
			notification.getId(),
			notification.getType().getDescription(),
			notification.getMessage(),
			notification.getType().makeUrl(notification.getReferenceId()),
			notification.isRead(),
			notification.getType().name(),
			notification.getCreatedAt()
		);
	}
}
