package com.bugzero.rarego.in;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.app.NotificationSseSupport;
import com.bugzero.rarego.event.NotificationCreatedEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NotificationEventListener {
	private final NotificationSseSupport notificationSseSupport;

	@Async
	@EventListener
	public void handleNotificationCreatedEvent(NotificationCreatedEvent event) {
		notificationSseSupport.send(event.publicId(), event.response());
	}
}
