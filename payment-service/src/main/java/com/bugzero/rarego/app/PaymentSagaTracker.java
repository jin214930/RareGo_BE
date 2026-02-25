package com.bugzero.rarego.app;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.PaymentSagaExecution;
import com.bugzero.rarego.domain.PaymentSagaType;
import com.bugzero.rarego.out.PaymentSagaExecutionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSagaTracker {
	private final PaymentSagaExecutionRepository sagaRepository;

	@Value("${payment.saga.retry.max-attempts:5}")
	private int maxRetryAttempts;

	@Value("${payment.saga.retry.delay-seconds:300}")
	private long retryDelaySeconds;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public String startOrResume(PaymentSagaType sagaType, String businessKey, Enum<?> initialStep) {
		String stepName = initialStep.name();
		PaymentSagaExecution saga = sagaRepository.findBySagaTypeAndBusinessKey(sagaType, businessKey)
			.map(existing -> {
				existing.restart(stepName);
				return existing;
			})
			.orElseGet(() -> PaymentSagaExecution.start(sagaType, businessKey, stepName));

		sagaRepository.save(saga);
		log.info("Saga 시작/재개: type={}, businessKey={}, step={}, commandId={}",
			sagaType, businessKey, stepName, saga.getCommandId());
		return saga.getCommandId();
	}

	// Recovery 경로에서는 이미 FOR UPDATE로 읽은 엔티티를 같은 트랜잭션에서 갱신한다.
	public String startOrResume(PaymentSagaExecution saga, Enum<?> initialStep) {
		String stepName = initialStep.name();
		saga.restart(stepName);
		log.info("Saga 시작/재개(동일 TX): type={}, businessKey={}, step={}, commandId={}",
			saga.getSagaType(), saga.getBusinessKey(), stepName, saga.getCommandId());
		return saga.getCommandId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markStep(PaymentSagaType sagaType, String businessKey, Enum<?> step) {
		sagaRepository.findBySagaTypeAndBusinessKey(sagaType, businessKey).ifPresent(saga -> {
			saga.markStep(step.name());
			log.info("Saga 단계 전이: type={}, businessKey={}, step={}, commandId={}",
				sagaType, businessKey, step.name(), saga.getCommandId());
		});
	}

	public void markStep(PaymentSagaExecution saga, Enum<?> step) {
		saga.markStep(step.name());
		log.info("Saga 단계 전이(동일 TX): type={}, businessKey={}, step={}, commandId={}",
			saga.getSagaType(), saga.getBusinessKey(), step.name(), saga.getCommandId());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markCheckpoint(PaymentSagaType sagaType, String businessKey, Enum<?> checkpointStep) {
		sagaRepository.findBySagaTypeAndBusinessKey(sagaType, businessKey).ifPresent(saga -> {
			saga.markCheckpoint(checkpointStep.name());
			log.info("Saga 체크포인트 갱신: type={}, businessKey={}, checkpointStep={}, commandId={}",
				sagaType, businessKey, checkpointStep.name(), saga.getCommandId());
		});
	}

	public void markCheckpoint(PaymentSagaExecution saga, Enum<?> checkpointStep) {
		saga.markCheckpoint(checkpointStep.name());
		log.info("Saga 체크포인트 갱신(동일 TX): type={}, businessKey={}, checkpointStep={}, commandId={}",
			saga.getSagaType(), saga.getBusinessKey(), checkpointStep.name(), saga.getCommandId());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markCompleted(PaymentSagaType sagaType, String businessKey, Enum<?> finalStep) {
		sagaRepository.findBySagaTypeAndBusinessKey(sagaType, businessKey).ifPresent(saga -> {
			saga.markCompleted(finalStep.name());
			log.info("Saga 완료: type={}, businessKey={}, finalStep={}, commandId={}",
				sagaType, businessKey, finalStep.name(), saga.getCommandId());
		});
	}

	public void markCompleted(PaymentSagaExecution saga, Enum<?> finalStep) {
		saga.markCompleted(finalStep.name());
		log.info("Saga 완료(동일 TX): type={}, businessKey={}, finalStep={}, commandId={}",
			saga.getSagaType(), saga.getBusinessKey(), finalStep.name(), saga.getCommandId());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(PaymentSagaType sagaType, String businessKey, Enum<?> failedStep, Exception ex) {
		sagaRepository.findBySagaTypeAndBusinessKey(sagaType, businessKey).ifPresent(saga -> {
			boolean retryable = saga.getAttemptCount() < maxRetryAttempts;
			LocalDateTime nextRetryAt = retryable ? LocalDateTime.now().plusSeconds(retryDelaySeconds) : null;
			saga.markFailed(failedStep.name(), ex, nextRetryAt, retryable);
			log.error(
				"Saga 실패: type={}, businessKey={}, failedStep={}, commandId={}, attempt={}, retryable={}, nextRetryAt={}, error={}",
				sagaType, businessKey, failedStep.name(), saga.getCommandId(), saga.getAttemptCount(), retryable,
				nextRetryAt, ex.getMessage(), ex);
		});
	}

	public void markFailed(PaymentSagaExecution saga, Enum<?> failedStep, Exception ex) {
		boolean retryable = saga.getAttemptCount() < maxRetryAttempts;
		LocalDateTime nextRetryAt = retryable ? LocalDateTime.now().plusSeconds(retryDelaySeconds) : null;
		saga.markFailed(failedStep.name(), ex, nextRetryAt, retryable);
		log.error(
			"Saga 실패(동일 TX): type={}, businessKey={}, failedStep={}, commandId={}, attempt={}, retryable={}, nextRetryAt={}, error={}",
			saga.getSagaType(), saga.getBusinessKey(), failedStep.name(), saga.getCommandId(), saga.getAttemptCount(),
			retryable, nextRetryAt, ex.getMessage(), ex);
	}
}
