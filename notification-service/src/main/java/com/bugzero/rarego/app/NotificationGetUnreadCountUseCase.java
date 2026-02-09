package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.NotificationMember;
import com.bugzero.rarego.in.dto.NotificationUnreadCountResponse;
import com.bugzero.rarego.out.NotificationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationGetUnreadCountUseCase {
	private final NotificationSupport notificationSupport;
	private final NotificationRepository notificationRepository;

	@Transactional(readOnly = true)
	public NotificationUnreadCountResponse getUnreadCount(String publicId) {
		NotificationMember member = notificationSupport.findMemberByPublicId(publicId);

		long count = notificationRepository.countAllByMemberIdAndIsReadFalse(member.getId());

		return NotificationUnreadCountResponse.from(count);
	}
}
