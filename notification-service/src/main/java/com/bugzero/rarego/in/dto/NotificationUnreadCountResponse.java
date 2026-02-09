package com.bugzero.rarego.in.dto;

public record NotificationUnreadCountResponse(
	long count
) {
	public static NotificationUnreadCountResponse from(long count) {
		return new NotificationUnreadCountResponse(count);
	}
}
