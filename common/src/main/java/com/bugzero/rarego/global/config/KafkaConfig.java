package com.bugzero.rarego.global.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.JsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.mapping.DefaultJackson2JavaTypeMapper;
import org.springframework.kafka.support.mapping.Jackson2JavaTypeMapper;
import org.springframework.util.backoff.FixedBackOff;

import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionOutbidEvent;
import com.bugzero.rarego.shared.auction.event.AuctionRelistedEvent;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent;
import com.bugzero.rarego.shared.member.event.MemberJoinedEvent;
import com.bugzero.rarego.shared.member.event.MemberUpdatedEvent;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentCompletedEvent;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentExpiringSoonEvent;
import com.bugzero.rarego.shared.payment.event.PaymentTimeoutEvent;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;
import com.bugzero.rarego.shared.product.event.ProductCreateAuctionEvent;
import com.bugzero.rarego.shared.product.event.ProductDeleteAuctionEvent;
import com.bugzero.rarego.shared.product.event.ProductUpdateAuctionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@EnableKafka
@Configuration
@Slf4j
public class KafkaConfig {
	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	@Value("${spring.application.name}")
	private String applicationName;

	// [중요] DLT 발행을 위한 Recoverer 설정
	@Bean
	public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, Object> kafkaTemplate) {
		// 실패 시 "원본토픽명.DLT"로 메시지를 전송합니다.
		// 이때 전송 실패 원인(Exception) 정보가 헤더에 자동으로 포함됩니다.
		return new DeadLetterPublishingRecoverer(kafkaTemplate);
	}

	// [중요] 중앙 집중식 에러 핸들러 (Retry + DLT)
	@Bean
	public DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer recoverer) {
		// 1초 간격으로 최대 3번 재시도 (첫 시도 포함 총 3번)
		FixedBackOff backOff = new FixedBackOff(1000L, 2L);

		DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

		// 로직 수행 중 에러 발생 시 로그 출력
		errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
			log.warn("[KafkaRetry] {}회차 시도 실패. Topic: {}, Offset: {}, Error: {}",
				deliveryAttempt, record.topic(), record.offset(), ex.getMessage());
		});

		// 특정 예외는 재시도 없이 바로 DLT로 보낼 때 추가 (선택 사항)
		// errorHandler.addNotRetryableExceptions(CustomMappingException.class);

		return errorHandler;
	}

	// ==========================
	// 1. Producer 설정 (Outbox 전용)
	// ==========================
	@Bean
	public ProducerFactory<String, Object> producerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

		// 중요: DB의 JSON String을 그대로 내보내기 위해 StringSerializer 사용
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

		return new DefaultKafkaProducerFactory<>(config);
	}

	@Bean
	public KafkaTemplate<String, Object> kafkaTemplate() {
		return new KafkaTemplate<>(producerFactory());
	}

	// ==========================
	// 2. Consumer 설정 (다중 타입 매핑)
	// ==========================
	@Bean
	public ConsumerFactory<String, Object> consumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, applicationName + "-group");
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

		// 리시버는 일단 String으로 받고, 아래의 Converter가 객체로 변환합니다.
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

		return new DefaultKafkaConsumerFactory<>(config);
	}

	@Bean
	public RecordMessageConverter multiTypeConverter(ObjectMapper objectMapper) {
		// 전송 시 헤더에 담긴 __TypeId__를 보고 어떤 객체로 바꿀지 결정합니다.
		JsonMessageConverter converter = new JsonMessageConverter(objectMapper);
		DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();

		typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
		typeMapper.addTrustedPackages("com.bugzero.rarego.*", "java.util.*", "java.lang.*");

		// 이벤트 타입별 클래스 매핑
		Map<String, Class<?>> mappings = new HashMap<>();
		mappings.put("ProductCreateAuctionEvent", ProductCreateAuctionEvent.class);
		mappings.put("ProductUpdateAuctionEvent", ProductUpdateAuctionEvent.class);
		mappings.put("ProductDeleteAuctionEvent", ProductDeleteAuctionEvent.class);

		mappings.put("MemberJoinedEvent", MemberJoinedEvent.class);
		mappings.put("MemberUpdatedEvent", MemberUpdatedEvent.class);
		mappings.put("AuctionEndedEvent", AuctionEndedEvent.class);
		mappings.put("AuctionStartedEvent", AuctionStartedEvent.class);
		mappings.put("AuctionRelistedEvent", AuctionRelistedEvent.class);
		mappings.put("AuctionOutbidEvent", AuctionOutbidEvent.class);

		mappings.put("AuctionEndedEvent", AuctionEndedEvent.class);
		mappings.put("AuctionStartedEvent", AuctionStartedEvent.class);
		mappings.put("AuctionRelistedEvent", AuctionRelistedEvent.class);
		// payment
		mappings.put("AuctionPaymentCompletedEvent", AuctionPaymentCompletedEvent.class);
		mappings.put("AuctionPaymentExpiringSoonEvent", AuctionPaymentExpiringSoonEvent.class);
		mappings.put("SettlementFinishedEvent", SettlementFinishedEvent.class);
		mappings.put("PaymentTimeoutEvent", PaymentTimeoutEvent.class);

		typeMapper.setIdClassMapping(mappings);
		converter.setTypeMapper(typeMapper);

		return converter;
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
		ConsumerFactory<String, Object> consumerFactory,
		RecordMessageConverter multiTypeConverter,
		DefaultErrorHandler errorHandler) { // 에러 핸들러 주입

		ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		factory.setRecordMessageConverter(multiTypeConverter);

		// 1. 중앙에서 관리하는 에러 핸들러 등록
		factory.setCommonErrorHandler(errorHandler);

		// 2. AckMode 설정
		// BATCH 모드도 작동하지만, DLT와 정밀한 재시도를 위해 RECORD 혹은 BATCH를 유지하되
		// 에러 핸들러가 오프셋을 제어하도록 맡깁니다.
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

		return factory;
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> dltContainerFactory(
		ConsumerFactory<String, Object> consumerFactory,
		RecordMessageConverter multiTypeConverter) {

		ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		factory.setRecordMessageConverter(multiTypeConverter);

		// [핵심] CommonErrorHandler를 설정하지 않습니다.
		// 이렇게 하면 이 팩토리를 사용하는 리스너에서 에러가 나도 재시도나 DLT 재발행을 하지 않습니다.

		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
		return factory;
	}
}
