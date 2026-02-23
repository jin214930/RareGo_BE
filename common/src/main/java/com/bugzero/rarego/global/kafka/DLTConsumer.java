package com.bugzero.rarego.global.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.global.slack.SlackNotifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DLTConsumer {

	@Value("${spring.application.name}")
	private String applicationName;

	private final SlackNotifier slackNotifier;

	@KafkaListener(
		topicPattern = ".*[.-](?i)dlt",
		groupId = "${spring.application.name}-global-dlt-group",
		containerFactory = "dltContainerFactory"
	)
	public void processDlt(ConsumerRecord<String, String> record) {
		try {
			String originalTopic = getHeaderValue(record, "kafka_dlt-original-topic");
			String errorMessage = getHeaderValue(record, "kafka_dlt-exception-message");
			String messageId = getHeaderValue(record, "messageId");

			log.error("============= [Global DLT Monitor] =============");
			log.error("발생 서비스: {}", applicationName);
			log.error("원본 토픽: {}", originalTopic);
			log.error("메시지 ID: {}", messageId);
			log.error("에러 내용: {}", errorMessage);
			log.error("데이터: {}", record.value());
			log.error("===============================================");

			String slackMessage = """
				🚨 *[DLT 알림]* 메시지 처리 실패
				• 서비스: %s
				• 원본 토픽: %s
				• 메시지 ID: %s
				• 에러: %s
				• 데이터: %.200s
				""".formatted(
				applicationName,
				originalTopic,
				messageId,
				truncate(errorMessage, 300),
				record.value()
			);

			slackNotifier.send(slackMessage);

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

	private String truncate(String text, int maxLength) {
		if (text == null || text.length() <= maxLength) {
			return text;
		}
		return text.substring(0, maxLength) + "...";
	}
}
