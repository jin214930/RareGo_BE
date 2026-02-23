package com.bugzero.rarego.global.slack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SlackNotifier {

	private final RestClient restClient;
	private final String webhookUrl;
	private final boolean enabled;

	public SlackNotifier(
		@Value("${custom.slack.webhook-url:}") String webhookUrl,
		@Value("${custom.slack.enabled:false}") boolean enabled
	) {
		this.restClient = RestClient.create();
		this.webhookUrl = webhookUrl;
		this.enabled = enabled;
	}

	public void send(String message) {
		if (!enabled || webhookUrl.isBlank()) {
			log.debug("Slack 알림이 비활성화 상태입니다.");
			return;
		}

		try {
			String payload = """
				{"text": %s}
				""".formatted(escapeJson(message));

			restClient.post()
				.uri(webhookUrl)
				.header("Content-Type", "application/json")
				.body(payload)
				.retrieve()
				.toBodilessEntity();

			log.info("Slack 알림 전송 완료");
		} catch (Exception e) {
			log.error("Slack 알림 전송 실패: {}", e.getMessage());
		}
	}

	private String escapeJson(String text) {
		return "\"" + text
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			+ "\"";
	}
}
