package com.bugzero.rarego.global.outbox.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.outbox.domain.OutboxEvent;
import com.bugzero.rarego.global.outbox.domain.OutboxStatus;
import com.bugzero.rarego.global.outbox.repository.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${outbox.poller.batch-size:100}") // 각 모듈에 적합하게 설정
	private int batchSize;

	@Value("${outbox.poller.max-retry:5}") // 각 모듈에 적합하게 설정
	private int maxRetry;

	@Scheduled(fixedDelayString = "${outbox.poller.interval-ms:5000}") // 각 모듈에 적합하게 설정
	@Transactional
	public void pollAndPublish() {
		List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAt(
			OutboxStatus.PENDING,
			PageRequest.of(0, batchSize)
		);

		if (pendingEvents.isEmpty()) {
			return;
		}

		log.info("아웃박스에 저장된 데이터 {} 개가 카프카 메시지로 전송중입니다.", pendingEvents.size());

		for (OutboxEvent event : pendingEvents) {
			processEvent(event);
		}
	}

	private void processEvent(OutboxEvent event) {
		try {
			event.markProcessing();

			kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
				.get(5, TimeUnit.SECONDS);

			event.markSent();
			log.debug("카프카 메시지 전송에 성공했습니다.: id={}, topic={}", event.getId(), event.getTopic());

		} catch (Exception e) {
			log.error("카프카 메시지 전송에 실패했습니다.: id={}, error={}", event.getId(), e.getMessage());
			event.markFailed(e.getMessage(), maxRetry);
		}
	}

	@Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * *}")
	@Transactional
	public void cleanupOldEvents() {
		LocalDateTime threshold = LocalDateTime.now().minusDays(7);
		int deletedCount = outboxEventRepository.deleteSentEventsBefore(threshold);
		if (deletedCount > 0) {
			log.info("전송완료된 아웃박스 데이터가 {} 개 삭제되었습니다.", deletedCount);
		}
	}

}
