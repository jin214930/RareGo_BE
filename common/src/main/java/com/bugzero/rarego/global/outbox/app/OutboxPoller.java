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

	@Scheduled(fixedDelayString = "${outbox.poller.interval-ms:10000}") // 각 모듈에 적합하게 설정
	@Transactional
	public void pollAndPublish() {
		List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAt(
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
			record.headers().add("messageId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
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
	@Transactional
	public void cleanupOldEvents() {
		LocalDateTime threshold = LocalDateTime.now().minusDays(7);
		int deletedCount = outboxEventRepository.deleteSentEventsBefore(threshold);
		if (deletedCount > 0) {
			log.info("전송완료된 아웃박스 데이터가 {} 개 삭제되었습니다.", deletedCount);
		}
	}

}
