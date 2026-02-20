package com.bugzero.rarego.global.outbox.app;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.outbox.domain.OutboxEvent;
import com.bugzero.rarego.global.outbox.domain.OutboxStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

	private final OutboxUseCase outboxUseCase;
	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${outbox.poller.batch-size:100}") // 각 모듈에 적합하게 설정
	private int batchSize;

	@Value("${outbox.poller.max-retry:5}") // 각 모듈에 적합하게 설정
	private int maxRetry;

	@Scheduled(fixedDelayString = "${outbox.poller.interval-ms:10000}") // 각 모듈에 적합하게 설정
	@Transactional
	public void pollAndPublish() {
		List<OutboxEvent> pendingEvents = outboxUseCase.findByStatusOrderByCreatedAt(
			OutboxStatus.PENDING,
			PageRequest.of(0, batchSize)
		);

		log.info("아웃박스에 저장된 데이터 {} 개를 가져왔습니다.", pendingEvents.size());


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

			// 1. ProducerRecord 직접 생성 (Object 데이터임을 명시)
			ProducerRecord<String, Object> record = new ProducerRecord<>(
				event.getTopic(),
				event.getAggregateId(),
				event.getPayload()
			);

			// 2. 헤더 직접 추가 (스프링 카프카 표준 헤더 사용)
			// 카프카 헤더는 바이트 배열을 받으므로 getBytes() 처리가 필요합니다.
			record.headers().add("__TypeId__", event.getEventType().getBytes(StandardCharsets.UTF_8));
			record.headers().add("messageId", event.generateMessageId().getBytes(StandardCharsets.UTF_8));
			record.headers().add("aggregateType", event.getAggregateType().getBytes(StandardCharsets.UTF_8));

			// 3. 전송 (StringSerializer가 이 레코드를 정상적으로 처리합니다)
			kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

			event.markSent();
			log.info("카프카 메시지 전송 성공: id={}, topic={}, eventType={}", event.getId(), event.getTopic(), event.getEventType());

		} catch (Exception e) {
			log.error("카프카 메시지 전송에 실패했습니다.: id={}, error={}, eventType={}", event.getId(), e.getMessage(),  event.getEventType());
			event.markFailed(e.getMessage(), maxRetry);
		}
	}

	@Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * *}")
	public void cleanupOldEvents() {
		LocalDateTime threshold = LocalDateTime.now().minusDays(7);
		int batchSize = 100; // 소규모 배치 (인박스와 동일)
		long totalDeleted = 0;

		log.info("[OutboxCleanup] {} 이전의 전송 완료된 데이터 정리를 시작합니다.", threshold);

		// 최대 50번 시도 (총 5,000건) - 무한 루프 방지
		for (int i = 0; i < 50; i++) {
			try {
				int deletedCount = outboxUseCase.deleteSentEventsBatch(threshold, batchSize);
				totalDeleted += deletedCount;

				if (deletedCount < batchSize) {
					break; // 더 이상 지울 데이터가 없음
				}

				Thread.sleep(50); // DB 숨 고르기

			} catch (Exception e) {
				log.error("[OutboxCleanup] {}번째 배치 처리 중 오류 발생. 다음 배치를 계속합니다.", i + 1, e);
			}
		}

		if (totalDeleted > 0) {
			log.info("[OutboxCleanup] 정리 완료. 총 {}건 삭제되었습니다.", totalDeleted);
		}
	}

}
