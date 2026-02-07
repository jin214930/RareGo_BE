package com.bugzero.rarego.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;

import com.bugzero.rarego.shared.payment.dto.SettlementResponseDto;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentCompletedEvent;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentExpiringSoonEvent;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

@SpringBootTest(classes = {KafkaAutoConfiguration.class})
@TestPropertySource(properties = {
	"spring.kafka.bootstrap-servers=localhost:29092",
	"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer"
})
class PaymentKafkaConnectionTest {

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	// Bridge에서 사용하는 토픽명과 일치해야 함
	private static final String TOPIC_SETTLEMENT = "payment-settlement-finished";
	private static final String TOPIC_PAYMENT_COMPLETED = "payment-auction-completed";
	private static final String TOPIC_EXPIRING_SOON = "payment-auction-expiring-soon";

	@Test
	@DisplayName("1. 정산 완료 이벤트(SettlementFinishedEvent) 전송 테스트")
	void testSend_SettlementFinished() {
		// 1. DTO 생성 (Record 생성자 사용 - 8개 필드)
		SettlementResponseDto dto1 = new SettlementResponseDto(
			1L,                 // id
			100L,               // auctionId
			200L,               // sellerId
			10000,              // salesAmount (판매가)
			1000,               // feeAmount (수수료)
			9000,               // settlementAmount (정산금)
			"COMPLETED",        // status
			LocalDateTime.now() // createdAt
		);

		List<SettlementResponseDto> settlements = List.of(dto1);

		// 2. 이벤트 생성 (of 메서드 대신 생성자 직접 사용)
		// totalCount와 totalAmount를 직접 넣어줍니다.
		SettlementFinishedEvent event = new SettlementFinishedEvent(
			settlements,
			1,     // totalCount
			9000   // totalAmount
		);

		String key = UUID.randomUUID().toString();

		// 3. 전송 및 로그 확인
		sendAndLog(TOPIC_SETTLEMENT, key, event);
	}

	@Test
	@DisplayName("2. 낙찰 결제 완료 이벤트(AuctionPaymentCompletedEvent) 전송 테스트")
	void testSend_AuctionPaymentCompleted() {
		// given
		AuctionPaymentCompletedEvent event = new AuctionPaymentCompletedEvent(
			123L, // orderId
			456L, // auctionId
			999L, // sellerId (추가됨)
			789L, // buyerId
			15000 // amount
		);

		String key = String.valueOf(event.auctionId()); // Key: auctionId

		// when & then
		sendAndLog(TOPIC_PAYMENT_COMPLETED, key, event);
	}

	@Test
	@DisplayName("3. 마감 임박 알림 이벤트(AuctionPaymentExpiringSoonEvent) 전송 테스트")
	void testSend_ExpiringSoon() {
		// given
		AuctionPaymentExpiringSoonEvent event = new AuctionPaymentExpiringSoonEvent(
			111L, // orderId
			222L, // auctionId
			333L, // buyerId
			444L, // sellerId
			20000, // amount
			LocalDateTime.now().plusHours(12) // expiredAt
		);

		String key = String.valueOf(event.auctionId()); // Key: auctionId

		// when & then
		sendAndLog(TOPIC_EXPIRING_SOON, key, event);
	}

	/**
	 * 전송 및 결과 로그 출력 헬퍼 메서드
	 */
	private void sendAndLog(String topic, String key, Object event) {
		try {
			System.out.println(">>> 전송 시도: Topic=" + topic + ", Key=" + key);

			// 실제 전송 (최대 3초 대기)
			SendResult<String, Object> result = kafkaTemplate.send(topic, key, event)
				.get(3, TimeUnit.SECONDS);

			System.out.println("✅ 전송 성공!");
			System.out.println("   - Offset: " + result.getRecordMetadata().offset());
			System.out.println("   - Partition: " + result.getRecordMetadata().partition());
			System.out.println("--------------------------------------------------");

		} catch (Exception e) {
			System.err.println("❌ 전송 실패: " + e.getMessage());
			throw new RuntimeException("Kafka 전송 실패", e);
		}
	}
}
