package com.bugzero.rarego.ai.app;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.ai.domain.dto.AiExternalPriceRequestDto;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Service
@Slf4j
public class AiGetExternalPriceUseCase {

	private final ChatClient chatClient;
	private final Duration apiTimeout;

	public AiGetExternalPriceUseCase(ChatClient chatClient,
		@Value("${ai.api.timeout:30s}") Duration apiTimeout) {
		this.chatClient = chatClient;
		this.apiTimeout = apiTimeout;
	}

	public Flux<String> execute(AiExternalPriceRequestDto dto) {
		// 사용자의 입력값을 프롬프트에 주입
		String userPrompt = String.format(
			"상품 정보:\n- 카테고리: %s\n- 모델명: %s\n- 상태: %s\n" +
				"위 정보를 바탕으로 현재 이 레고의 시장 가치를 평가하고, 근거와 참조 링크를 포함해 답변해줘.",
			dto.category(), dto.name(), dto.condition().getDescription()
		);

		return chatClient.prompt()
			.user(userPrompt)
			.stream()
			.content()
			.timeout(apiTimeout) // 주입받은 타임아웃 적용
			.onErrorResume(TimeoutException.class, e -> {
				log.error("AI 응답 타임아웃 발생 ({}): {}", apiTimeout, e.getMessage());
				return Flux.just("응답 시간이 초과되었습니다. 다시 시도해주세요.");
			})
			.onErrorResume(e -> {
				log.error("AI 분석 중 오류 발생: {}", e.getMessage());
				return Flux.just("서비스 이용 중 오류가 발생했습니다.");
			});
	}

}
