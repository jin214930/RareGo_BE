package com.bugzero.rarego.global.outbox.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.kafka.KafkaTopics;
import com.bugzero.rarego.global.outbox.repository.OutboxEventRepository;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.shared.product.dto.ProductAuctionCreateDto;
import com.bugzero.rarego.shared.product.event.ProductCreateAuctionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxUseCaseTest {

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private ObjectMapper objectMapper;

	@InjectMocks
	private OutboxUseCase outboxUseCase;

	@Test
	@DisplayName("상품 경매 생성 이벤트가 들어오면 Product 메타데이터를 추출하고 저장한다")
	void should_SaveOutboxEvent_When_ProductCreateAuctionEventProvided() throws JsonProcessingException {
		// given
		// 1. 테스트용 DTO 및 이벤트 생성 (Builder 활용)
		ProductAuctionCreateDto dto = ProductAuctionCreateDto.builder()
			.startPrice(1000)
			.durationDays(7)
			.build();

		ProductCreateAuctionEvent event = ProductCreateAuctionEvent.builder()
			.productId(101L)
			.publicId("PROD-ABC-123")
			.dto(dto)
			.build();

		String expectedPayload = "{\"productId\":101, \"startPrice\":1000, \"durationDays\":7}";

		// ObjectMapper 동작 정의
		when(objectMapper.writeValueAsString(event)).thenReturn(expectedPayload);

		// when
		outboxUseCase.saveOutbox(event);

		// then
		// resolveMetadata의 'Product' 분기 로직 검증
		verify(outboxEventRepository).save(argThat(outboxEvent ->
			outboxEvent.getAggregateType().equals("Product") && // 메타데이터 타입 확인
				outboxEvent.getAggregateId().equals("101") &&       // productId가 식별자로 쓰였는지 확인
				outboxEvent.getTopic().equals(KafkaTopics.AUCTION_INFO_MANAGEMENT.getTopicName()) && // 토픽 확인
				outboxEvent.getPayload().equals(expectedPayload)    // 직렬화된 데이터 확인
		));
	}

	@Test
	@DisplayName("지원하지 않는 일반 객체가 들어오면 UNSUPPORTED_OUTBOX_EVENT 예외를 던진다")
	void should_ThrowException_When_UnsupportedEventProvided() {
		// given
		Object unsupportedEvent = new Object();

		// when & then
		CustomException exception = assertThrows(CustomException.class, () ->
			outboxUseCase.saveOutbox(unsupportedEvent)
		);
		assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNSUPPORTED_OUTBOX_EVENT);
	}

	@Test
	@DisplayName("JSON 직렬화 실패 시 JSON_SERIALIZATION_FAILED 예외를 던진다")
	void should_ThrowException_When_JsonSerializationFails() throws JsonProcessingException {
		// given
		ProductCreateAuctionEvent event = ProductCreateAuctionEvent.builder()
			.productId(101L)
			.build();

		when(objectMapper.writeValueAsString(any())).thenThrow(JsonProcessingException.class);

		// when & then
		CustomException exception = assertThrows(CustomException.class, () ->
			outboxUseCase.saveOutbox(event)
		);
		assertThat(exception.getErrorType()).isEqualTo(ErrorType.JSON_SERIALIZATION_FAILED);
	}

}
