package com.bugzero.rarego.global.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractDLTConsumer {

	@Value("${spring.application.name}")
	private String applicationName;

	/**
	 * 공통 실행 메서드: 각 서비스 리스너가 호출할 대상입니다.
	 */
	protected void process(ConsumerRecord<String, String> record) {
		try {
			log.error("============= [Global DLT Monitor - {}] =============", applicationName.toUpperCase());

			// 우리가 확인한 실제 헤더 키값 적용
			String originalTopic = getHeaderValue(record, "kafka_dlt-original-topic");
			String errorMessage = getHeaderValue(record, "kafka_dlt-exception-message");
			String messageId = getHeaderValue(record, "messageId");

			log.error("원본 토픽: {}", originalTopic);
			log.error("메시지 ID: {}", messageId);
			log.error("에러 내용: {}", errorMessage);
			log.error("데이터: {}", record.value());
			log.error("====================================================");

			// 추가 로직(슬랙 알림 등)이 필요한 경우 하위 클래스에서 처리하도록 훅을 제공합니다.
			handleCustomLogic(originalTopic, errorMessage, record.value());

		} catch (Exception e) {
			log.error("DLT 로깅 처리 중 예외 발생: {}", e.getMessage());
		}
	}

	// 하위 클래스에서 선택적으로 오버라이딩 (예: 슬랙 알림 전송)
	protected void handleCustomLogic(String topic, String error, String payload) {
		// 기본값은 빈 로직
	}

	private String getHeaderValue(ConsumerRecord<?, ?> record, String headerKey) {
		Header header = record.headers().lastHeader(headerKey);
		if (header != null && header.value() != null) {
			return new String(header.value(), StandardCharsets.UTF_8);
		}
		return "UNKNOWN";
	}
}
