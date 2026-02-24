package com.bugzero.rarego.global.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;

import com.bugzero.rarego.global.slack.SlackNotifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractDLTConsumer {

	@Value("${spring.application.name}")
	private String applicationName;

	private final SlackNotifier slackNotifier;

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

			handleCustomLogic(originalTopic, errorMessage, record.value());

		} catch (Exception e) {
			log.error("DLT 로깅 처리 중 예외 발생: {}", e.getMessage());
		}
	}

	protected void handleCustomLogic(String topic, String error, String payload) {
		String slackMessage = """
			🚨 *[DLT 알림]* 메시지 처리 실패
			• 서비스: %s
			• 원본 토픽: %s
			• 에러: %s
			• 데이터: %.200s
			""".formatted(
			applicationName,
			topic,
			truncate(error, 300),
			payload
		);

		slackNotifier.send(slackMessage);
	}

	private String getHeaderValue(ConsumerRecord<?, ?> record, String headerKey) {
		Header header = record.headers().lastHeader(headerKey);
		if (header != null && header.value() != null) {
			return new String(header.value(), StandardCharsets.UTF_8);
		}
		return "UNKNOWN";
	}

	private String truncate(String text, int maxLength) {
		if (text == null || text.length() <= maxLength) {
			return text;
		}
		return text.substring(0, maxLength) + "...";
	}
}
