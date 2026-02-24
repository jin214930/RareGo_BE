package com.bugzero.rarego.product.in;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.global.kafka.AbstractDLTConsumer;

@Component
public class ProductDLTConsumer extends AbstractDLTConsumer {

	@KafkaListener(
		// SpEL을 활용해 본인 서비스명이 포함된 모든 DLT 토픽을 구독합니다.
		topicPattern = ".*\\." + "${spring.application.name}" + "\\.DLT",
		groupId = "${spring.application.name}-dlt-group",
		containerFactory = "dltContainerFactory"
	)
	public void onMessage(ConsumerRecord<String, String> record) {
		super.process(record); // 부모 클래스의 공통 로깅 실행
	}

	@Override
	protected void handleCustomLogic(String topic, String error, String payload) {
		// 필요하다면 여기서 전용 슬랙 알림이나 DB 복구 로직 수행
	}
}
