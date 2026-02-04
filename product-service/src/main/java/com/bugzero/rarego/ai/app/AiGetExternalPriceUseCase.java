package com.bugzero.rarego.ai.app;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.ai.domain.dto.AiExternalPriceRequestDto;

import reactor.core.publisher.Flux;

@Service
public class AiGetExternalPriceUseCase {

	private final ChatClient chatClient;

	public AiGetExternalPriceUseCase(ChatClient chatClient) {
		this.chatClient = chatClient;
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
			.content();
	}

}
