package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import com.bugzero.rarego.app.mapper.NotificationMapper;
import com.bugzero.rarego.domain.Notification;
import com.bugzero.rarego.domain.NotificationMember;
import com.bugzero.rarego.domain.NotificationType;
import com.bugzero.rarego.event.NotificationCreatedEvent;
import com.bugzero.rarego.out.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationCreateNotificationUseCaseTest {

	@Mock
	private NotificationRepository notificationRepository;

	@Mock
	private NotificationMapper<Object> notificationMapper;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private NotificationCreateNotificationUseCase useCase;

	@BeforeEach
	void setUp() {
		List<NotificationMapper<?>> mappers = List.of(notificationMapper);
		useCase = new NotificationCreateNotificationUseCase(notificationRepository, mappers, eventPublisher);
	}

	@Test
	@DisplayName("성공: 알림 저장 후 이벤트(NotificationCreatedEvent)가 정상적으로 발행된다.")
	void createNotification_success() {
		// given
		TestEvent event = new TestEvent(1L);
		Notification notification = mock(Notification.class);
		NotificationMember member = mock(NotificationMember.class);

		given(notificationMapper.supports(event)).willReturn(true);
		given(notificationMapper.map(event)).willReturn(List.of(notification));

		given(notification.getId()).willReturn(100L);
		given(notification.getMember()).willReturn(member);
		given(member.getPublicId()).willReturn("member-uuid");
		given(notification.getType()).willReturn(NotificationType.AUCTION_WON);
		given(notification.getMessage()).willReturn("메시지");
		given(notification.getReferenceId()).willReturn(50L);
		given(notification.getCreatedAt()).willReturn(LocalDateTime.now());
		given(notification.isRead()).willReturn(false);

		// when
		useCase.createNotification(event);

		// then
		then(notificationRepository).should(times(1)).save(notification);  // saveAndFlush → save
		then(eventPublisher).should(times(1)).publishEvent(any(NotificationCreatedEvent.class));
	}

	@Test
	@DisplayName("성공(중복무시): 중복 예외가 발생하면 로그를 남기고 종료하며, '이벤트는 발행하지 않는다'.")
	void createNotification_success_duplicate() {
		// given
		TestEvent event = new TestEvent(1L);
		Notification notification = mock(Notification.class);
		NotificationMember member = mock(NotificationMember.class);

		given(notificationMapper.supports(event)).willReturn(true);
		given(notificationMapper.map(event)).willReturn(List.of(notification));
		given(notification.getMember()).willReturn(member);

		DataIntegrityViolationException duplicateException =
			new DataIntegrityViolationException("Duplicate entry '1-OUTBID' for key 'uk_notification_dedup'");

		willThrow(duplicateException)
			.given(notificationRepository).save(notification);  // saveAndFlush → save

		// when
		useCase.createNotification(event);

		// then
		then(notificationRepository).should(times(1)).save(notification);  // saveAndFlush → save
		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("실패: 중복이 아닌 데이터 무결성 예외는 던져져야 하며, 이벤트는 발행되지 않는다.")
	void createNotification_fail_integrity_violation() {
		// given
		TestEvent event = new TestEvent(1L);
		Notification notification = mock(Notification.class);

		given(notificationMapper.supports(event)).willReturn(true);
		given(notificationMapper.map(event)).willReturn(List.of(notification));

		DataIntegrityViolationException otherException =
			new DataIntegrityViolationException("Column 'message' cannot be null");

		willThrow(otherException)
			.given(notificationRepository).save(notification);  // saveAndFlush → save

		// when & then
		assertThatThrownBy(() -> useCase.createNotification(event))
			.isInstanceOf(DataIntegrityViolationException.class);

		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("실패: 지원하지 않는 이벤트는 처리하지 않는다.")
	void createNotification_fail_not_supported() {
		// given
		TestEvent event = new TestEvent(1L);
		given(notificationMapper.supports(event)).willReturn(false);

		// when
		useCase.createNotification(event);

		// then
		then(notificationRepository).shouldHaveNoInteractions();
		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("실패: 매퍼 결과가 비어있으면 처리하지 않는다.")
	void createNotification_fail_empty_list() {
		// given
		TestEvent event = new TestEvent(1L);
		given(notificationMapper.supports(event)).willReturn(true);
		given(notificationMapper.map(event)).willReturn(Collections.emptyList());

		// when
		useCase.createNotification(event);

		// then
		then(notificationRepository).shouldHaveNoInteractions();
		then(eventPublisher).shouldHaveNoInteractions();
	}

	record TestEvent(Long id) {
	}
}
