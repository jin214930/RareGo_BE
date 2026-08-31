package com.bugzero.rarego.benchmark;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.app.PaymentProcessSettlementUseCase;
import com.bugzero.rarego.app.PaymentSettlementProcessor;
import com.bugzero.rarego.app.PaymentSupport;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.global.config.BatchConfig;
import com.bugzero.rarego.global.config.JpaConfig;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.global.outbox.domain.OutboxEvent;
import com.bugzero.rarego.global.outbox.repository.OutboxEventRepository;
import com.bugzero.rarego.in.SettlementBatchConfig;
import com.bugzero.rarego.in.SettlementPayoutBatchConfig;
import com.bugzero.rarego.out.SettlementBulkRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 실제 정산/Outbox 저장만 기동한다. 스케줄러, HTTP 서버, Kafka 발행은 측정 범위 밖이다. */
@Configuration
@EnableAutoConfiguration(excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@EntityScan(basePackageClasses = {Settlement.class, OutboxEvent.class})
@EnableJpaRepositories(basePackageClasses = {SettlementRepository.class, OutboxEventRepository.class})
@Import({BatchConfig.class, JpaConfig.class, SettlementBatchConfig.class, SettlementPayoutBatchConfig.class,
	PaymentSupport.class, PaymentProcessSettlementUseCase.class, PaymentSettlementProcessor.class,
	OutboxUseCase.class, LegacySettlementBatchConfig.class, LegacyPaymentProcessSettlementUseCase.class,
	LegacyPaymentSettlementProcessor.class, SettlementBulkRepository.class})
public class BenchmarkApplication {

	@Bean
	PaymentFacade paymentFacade(PaymentProcessSettlementUseCase preparation, PaymentSettlementProcessor payout) {
		// 정산 위임 메서드는 실제 Facade를 사용한다. 호출하지 않는 타 업무만 구성에서 제외한다.
		return new PaymentFacade(null, null, null, null, preparation, payout, null, null,
			null, null, null, null, null, null, null);
	}

	@Bean
	ObjectMapper objectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}

	@Bean
	KafkaTemplate<String, Object> kafkaTemplate() {
		// 발행 경로가 실수로 실행되면 측정을 실패시킨다. Outbox 저장은 실제 DB에 수행한다.
		return new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, Object>(
			Map.of("bootstrap.servers", "127.0.0.1:1"))) {
			@Override
			public CompletableFuture<SendResult<String, Object>> send(ProducerRecord<String, Object> record) {
				throw new IllegalStateException("Kafka는 정산 DB 실측 범위에 포함되지 않습니다.");
			}
		};
	}
}
