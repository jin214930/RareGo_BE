package com.bugzero.rarego.global.outbox.domain;

import java.time.LocalDateTime;

import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "OUTBOX_EVENT", indexes = @Index(name = "idx_outbox_status_created", columnList = "status, createdAt"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class OutboxEvent extends BaseIdAndTime {
	@Column(nullable = false, length = 100) // Producer 모듈
	private String aggregateType;

	@Column(nullable = false, length = 100)
	private String aggregateId;    // 도메인(Aggregate) ID -> 파티션 키로 활용 ex) auctionId, productId...

	@Column(nullable = false, length = 200)
	private String eventType;

	@Column(nullable = false, length = 200)
	private String topic;

	@Column(columnDefinition = "LONGTEXT")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Builder.Default
	private OutboxStatus status = OutboxStatus.PENDING;

	private LocalDateTime sentDate;

	@Column(columnDefinition = "int default 0")
	@Builder.Default
	private int retryCount = 0;

	@Column(length = 1000)
	private String lastErrorMessage;

	// 정적 팩토리 메서드
	public static OutboxEvent createOutboxEvent(String aggregateType, String aggregateId, String eventType, String topic, String payload) {
		return OutboxEvent.builder()
			.aggregateType(aggregateType)
			.aggregateId(aggregateId)
			.eventType(eventType)
			.topic(topic)
			.payload(payload)
			// requestId 필드를 따로 두지 않으므로 여기서 세팅할 필요 없음
			.build();
	}

	public void markSent() {
		this.status = OutboxStatus.SENT;
		this.sentDate = LocalDateTime.now();
	}

	public void markFailed(String errorMessage, int maxRetry) {
		this.retryCount += 1;
		this.lastErrorMessage = errorMessage;
		if (this.retryCount >= maxRetry) {
			this.status = OutboxStatus.FAILED;
		}
	}

	public void markProcessing() {
		this.status = OutboxStatus.PROCESSING;
	}

}
