package com.bugzero.rarego.kafka;

import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

@SpringBootTest(classes = {KafkaAutoConfiguration.class})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:29092",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer"
})
class KafkaRealConnectionTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    @DisplayName("로컬 카프카로 경매 종료 실제 메시지 전송 테스트")
    void testRealSendEnded() {
        AuctionEndedEvent event = new AuctionEndedEvent(999L, 777L, 150000, 888L);
        try {
            kafkaTemplate.send("auction-ended", "999", event).get();
            System.out.println("경매 종료 메시지 전송 성공!");
        } catch (Exception e) {
            System.err.println("전송 실패: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("로컬 카프카로 경매 시작 실제 메시지 전송 테스트")
    void testRealSendStarted() {
        // 1. 시작 이벤트 데이터 생성
        AuctionStartedEvent event = new AuctionStartedEvent(100L, 200L, LocalDateTime.now());

        // 2. 실제 카프카 전송 (토픽명: auction-started)
        try {
            kafkaTemplate.send("auction-started", "100", event).get();
            System.out.println("경매 시작 메시지 전송 성공!");
        } catch (Exception e) {
            System.err.println("전송 실패: " + e.getMessage());
        }
    }
}