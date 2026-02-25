package com.bugzero.rarego.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@Table(
	name = "PAYMENT_SAGA_EXECUTION",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_payment_saga_type_business_key", columnNames = {"saga_type", "business_key"})
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentSagaExecution extends BaseIdAndTime {
	private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

	@Enumerated(EnumType.STRING)
	@Column(name = "saga_type", nullable = false, length = 50)
	private PaymentSagaType sagaType;

	@Column(name = "business_key", nullable = false, length = 100)
	private String businessKey;

	@Column(name = "command_id", nullable = false, length = 64)
	private String commandId;

	@Column(name = "current_step", nullable = false, length = 100)
	private String currentStep;

	@Column(name = "checkpoint_step", length = 100)
	private String checkpointStep;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentSagaExecutionStatus status;

	@Column(name = "failed_step", length = 100)
	private String failedStep;

	@Column(name = "last_error_type", length = 200)
	private String lastErrorType;

	@Column(name = "last_error_message", length = ERROR_MESSAGE_MAX_LENGTH)
	private String lastErrorMessage;

	@Column(name = "attempt_count", nullable = false, columnDefinition = "int default 0")
	@Builder.Default
	private int attemptCount = 0;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;

	@Column(name = "next_retry_at")
	private LocalDateTime nextRetryAt;

	@Column(name = "retryable", nullable = false, columnDefinition = "tinyint(1) default 1")
	@Builder.Default
	private boolean retryable = true;

	public static PaymentSagaExecution start(PaymentSagaType sagaType, String businessKey, String initialStep) {
		PaymentSagaExecution saga = PaymentSagaExecution.builder()
			.sagaType(sagaType)
			.businessKey(businessKey)
			.commandId(UUID.randomUUID().toString())
			.currentStep(initialStep)
			.checkpointStep(initialStep)
			.status(PaymentSagaExecutionStatus.IN_PROGRESS)
			.build();
		saga.beginAttempt(initialStep);
		return saga;
	}

	public void restart(String initialStep) {
		this.commandId = UUID.randomUUID().toString();
		beginAttempt(initialStep);
	}

	public void markStep(String step) {
		this.currentStep = step;
		this.status = PaymentSagaExecutionStatus.IN_PROGRESS;
		clearFailureForRetry();
	}

	public void markCheckpoint(String step) {
		this.checkpointStep = step;
	}

	public void markCompleted(String step) {
		this.currentStep = step;
		this.checkpointStep = step;
		this.status = PaymentSagaExecutionStatus.COMPLETED;
		this.retryable = false;
		this.nextRetryAt = null;
		clearFailure();
	}

	public void markFailed(String failedStep, Exception ex, LocalDateTime nextRetryAt, boolean retryable) {
		this.status = PaymentSagaExecutionStatus.FAILED;
		this.failedStep = failedStep;
		this.lastErrorType = resolveErrorType(ex);
		this.lastErrorMessage = truncate(ex.getMessage());
		this.nextRetryAt = nextRetryAt;
		this.retryable = retryable;
	}

	private void beginAttempt(String initialStep) {
		this.currentStep = initialStep;
		this.status = PaymentSagaExecutionStatus.IN_PROGRESS;
		this.attemptCount += 1;
		this.lastAttemptAt = LocalDateTime.now();
		this.nextRetryAt = null;
		this.retryable = true;
		clearFailure();
	}

	private void clearFailure() {
		this.failedStep = null;
		this.lastErrorType = null;
		this.lastErrorMessage = null;
	}

	private void clearFailureForRetry() {
		this.failedStep = null;
		this.lastErrorType = null;
		this.lastErrorMessage = null;
		this.nextRetryAt = null;
	}

	private String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() > ERROR_MESSAGE_MAX_LENGTH
			? message.substring(0, ERROR_MESSAGE_MAX_LENGTH)
			: message;
	}

	private String resolveErrorType(Exception ex) {
		if (ex instanceof CustomException customException) {
			return customException.getErrorType().name();
		}
		return ex.getClass().getSimpleName();
	}
}
