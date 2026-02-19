package com.bugzero.rarego.global.inbox.app;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.global.inbox.repository.InboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboxScheduler {

	private final InboxEventRepository inboxEventRepository;

	/**
	 * 매일 새벽 3시에 7일이 지난 인박스 데이터를 삭제합니다.
	 * cron: 초 분 시 일 월 요일
	 */
	@Scheduled(cron = "0 0 3 * * *")
	public void cleanupOldInboxEvents() {
		LocalDateTime threshold = LocalDateTime.now().minusDays(7);
		log.info("[InboxCleanup] {} 이전의 인박스 데이터 삭제를 시작합니다.", threshold);

		try {
			inboxEventRepository.deleteByCreatedAtBefore(threshold);
			log.info("[InboxCleanup] 인박스 데이터 정리가 완료되었습니다.");
		} catch (Exception e) {
			log.error("[InboxCleanup] 인박스 데이터 정리 중 오류 발생", e);
		}
	}
}
