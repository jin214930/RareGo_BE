package com.bugzero.rarego.global.inbox.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.bugzero.rarego.global.inbox.domain.InboxEvent;
import com.bugzero.rarego.global.inbox.repository.InboxEventRepository;

@ExtendWith(MockitoExtension.class)
class InboxUseCaseTest {
	@Mock
	private InboxEventRepository inboxEventRepository;

	@InjectMocks
	private InboxUseCase inboxUseCase;

	private final String messageId = "Product-101";
	private final String consumerGroup = "auction-service-group";

	@Test
	@DisplayName("처음 유입된 메시지는 인박스에 저장하고 false를 반환한다")
	void should_ReturnFalse_When_MessageIsFirstTime() {
		// given
		when(inboxEventRepository.existsByMessageIdAndConsumerGroup(messageId, consumerGroup))
			.thenReturn(false);

		// when
		boolean result = inboxUseCase.isAlreadyProcessed(messageId, consumerGroup);

		// then
		assertThat(result).isFalse();
		verify(inboxEventRepository, times(1)).save(any(InboxEvent.class));
	}

	@Test
	@DisplayName("이미 DB에 기록된 메시지는 true를 반환하고 저장을 시도하지 않는다")
	void should_ReturnTrue_When_MessageAlreadyExists() {
		// given
		when(inboxEventRepository.existsByMessageIdAndConsumerGroup(messageId, consumerGroup))
			.thenReturn(true);

		// when
		boolean result = inboxUseCase.isAlreadyProcessed(messageId, consumerGroup);

		// then
		assertThat(result).isTrue();
		// 1차 체크에서 걸렸으므로 save는 호출되지 않아야 함
		verify(inboxEventRepository, never()).save(any(InboxEvent.class));
	}

	@Test
	@DisplayName("1차 체크는 통과했으나 저장 직전 다른 쓰레드가 먼저 저장한 경우(동시성 충돌) true를 반환한다")
	void should_ReturnTrue_When_DataIntegrityViolationOccurs() {
		// given
		// 1차 체크는 통과 (존재하지 않음)
		when(inboxEventRepository.existsByMessageIdAndConsumerGroup(messageId, consumerGroup))
			.thenReturn(false);

		// 하지만 save 시점에 누군가 먼저 저장해서 예외 발생
		doThrow(DataIntegrityViolationException.class)
			.when(inboxEventRepository).save(any(InboxEvent.class));

		// when
		boolean result = inboxUseCase.isAlreadyProcessed(messageId, consumerGroup);

		// then
		assertThat(result).isTrue(); // 예외를 잡아서 처리됨(true)으로 반환해야 함
	}

}
