package com.bugzero.rarego.global.inbox.app;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.inbox.domain.InboxEvent;
import com.bugzero.rarego.global.inbox.repository.InboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboxUseCase {

	private final InboxEventRepository inboxEventRepository;

	/**
	 * 메시지 처리 여부를 확인하고, 처음 받는 메시지라면 인박스에 기록합니다.
	 * * @param messageId "AggregateType-ID" 형식의 식별자
	 * @param consumerGroup 리스너의 그룹 ID
	 * @return true: 이미 처리됨 (중복), false: 처음 처리함 (진행 가능)
	 */
	@Transactional
	public boolean isAlreadyProcessed(String messageId, String consumerGroup) {
		// 1. 1차 체크
		if (inboxEventRepository.existsByMessageIdAndConsumerGroup(messageId, consumerGroup)) {
			log.info("[Inbox] 이미 처리된 메시지입니다. messageId: {}, group: {}", messageId, consumerGroup);
			return true;
		}

		// 2. 저장 시도
		try {
			InboxEvent inboxEvent = InboxEvent.createInboxEvent(messageId, consumerGroup);
			inboxEventRepository.save(inboxEvent);
			return false; // 성공적으로 저장됨 -> 처리 진행 가능
		} catch (DataIntegrityViolationException e) {
			// 3. 찰나의 순간에 다른 쓰레드가 먼저 저장한 경우 (Unique 제약 조건 위반)
			log.warn("[Inbox] 동시성 충돌 발생 - 이미 처리된 것으로 간주합니다. messageId: {}", messageId);
			return true;
		}
	}
}
