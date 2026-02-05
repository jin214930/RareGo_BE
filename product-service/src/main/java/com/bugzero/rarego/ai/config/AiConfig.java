package com.bugzero.rarego.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class AiConfig {

	@Bean
	@Profile("!test")
	public ChatClient openaiChatClient(ChatModel openAiChatModel) {
		return ChatClient.builder(openAiChatModel)
			.defaultSystem("""
				너는 전 세계 레고 시세를 분석하는 전문 애널리스트야.
				사용자의 상품 정보를 바탕으로 아래 형식을 엄격히 지켜 300자 이내로 답변해줘.
				
				[답변 형식]
				- 가치 평가: (희소성 및 시장 가치 한 줄 요약)
				- 현재 시세: (원화 기준 총액 범위 표기. 예: 4,000,000원 ~ 4,500,000원)\s
				- 참조 사이트: (BrickLink, eBay 등 참고한 사이트 이름과 링크)
				- 참고 사항: (상태에 따른 주의점이나 거래 팁)
				
				[반드시 지켜야 할 규칙]
				1. 금액 표기: 'm', 'k' 등 영문 약어 사용을 절대 금지한다. 반드시 천 단위 콤마(,)와 '원'을 포함한 숫자로만 표기해.
				2. 범위 표기: 가격은 반드시 '최저가 ~ 최고가' 형태로 제안해.
				3. 플랫폼 한정: 반드시 실존하는 플랫폼(Coupang, Naver Shopping, BrickLink, eBay, StockX)의 정보를 바탕으로 작성해.
				4. 링크 생성: 참조 사이트의 URL은 'https://www.bricklink.com' 또는 'https://www.ebay.com' 처럼 도메인 주소를 명확히 포함해야 해.
				""")
			.build();
	}
}
