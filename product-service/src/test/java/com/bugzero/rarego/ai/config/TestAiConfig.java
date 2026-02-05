package com.bugzero.rarego.ai.config;

import static org.mockito.Mockito.*;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestAiConfig {

	@Bean
	@Primary
	public ChatModel chatModel() {
		// ChatModel 자체를 빈으로 등록합니다.
		return mock(ChatModel.class);
	}

	@Bean
	@Primary
	public ChatClient chatClient(ChatModel chatModel) {
		// 위에서 만든 ChatModel 빈을 주입받아 ChatClient를 만듭니다.
		return ChatClient.builder(chatModel).build();
	}
}
