package com.bugzero.rarego.global.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DLTConsumer {

	@Value("${spring.application.name}")
	private String applicationName;

	/**
	 * 특정 도메인에 종속되지 않고, .DLT로 끝나는 모든 토픽을 구독합니다.
	 */
	@KafkaListener(
		topicPattern = ".*[.-](?i)dlt",
		groupId = "${spring.application.name}-global-dlt-group",
		// [핵심] 에러 핸들러가 없는 전용 팩토리를 사용하도록 명시합니다.
		containerFactory = "dltContainerFactory"
	)
	public void processDlt(ConsumerRecord<String, String> record) {
		try {
			log.error("============= [Global DLT Monitor] =============");

			// 로그에서 확인된 실제 키값을 직접 매핑합니다.
			String originalTopic = getHeaderValue(record, "kafka_dlt-original-topic");
			String errorMessage = getHeaderValue(record, "kafka_dlt-exception-message");
			String messageId = getHeaderValue(record, "messageId");

			log.error("발생 서비스: {}", applicationName);
			log.error("원본 토픽: {}", originalTopic);
			log.error("메시지 ID: {}", messageId);
			log.error("에러 내용: {}", errorMessage);
			log.error("데이터: {}", record.value());
			log.error("===============================================");

			// TODO: Slack 알림 시 errorMessage의 앞부분만 잘라서 보내면 깔끔합니다.
		} catch (Exception e) {
			log.error("DLT 로깅 중 에러 발생: {}", e.getMessage());
		}
	}

	private String getHeaderValue(ConsumerRecord<?, ?> record, String headerKey) {
		Header header = record.headers().lastHeader(headerKey);
		if (header != null && header.value() != null) {
			return new String(header.value(), StandardCharsets.UTF_8);
		}
		return "UNKNOWN";
	}
}
