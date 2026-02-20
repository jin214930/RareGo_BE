package com.bugzero.rarego.global.inbox.app;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboxScheduler {

	private final InboxUseCase inboxUseCase;

	/**
	 * 매일 새벽 3시에 7일이 지난 인박스 데이터를 삭제합니다.
	 * cron: 초 분 시 일 월 요일
	 */
	@Scheduled(cron = "0 0 3 * * *")
	public void cleanupOldInboxEvents() {
		LocalDateTime threshold = LocalDateTime.now().minusDays(7);
		int batchSize = 100; // 배치 사이즈를 작게 설정 (부하 최소화)
		long totalDeleted = 0;

		log.info("[InboxCleanup] {} 이전의 인박스 데이터 정리를 시작합니다. (BatchSize: {})", threshold, batchSize);

		// 하루 최대 삭제 제한을 두어 시스템 무한 루프 방지 (예: 최대 50번 시도 = 5천건)
		for (int i = 0; i < 50; i++) {
			try {
				// 한 번에 100건씩만 삭제하고 커밋
				int deletedCount = inboxUseCase.deleteBatch(threshold, batchSize);

				totalDeleted += deletedCount;

				if (deletedCount < batchSize) {
					break; // 더 이상 지울 데이터가 없음
				}

				// 각 배치 사이의 아주 짧은 휴식 (DB가 숨 고를 시간을 줌)
				Thread.sleep(50);

			} catch (Exception e) {
				log.error("[InboxCleanup] {}번째 배치 처리 중 오류 발생. 다음 배치를 계속합니다.", i + 1, e);
				// 한 번 실패해도 다음 100건 뭉텅이를 계속 시도함
			}
		}
		log.info("[InboxCleanup] 인박스 데이터 정리 완료. 총 {}건 삭제되었습니다.", totalDeleted);
	}
}
