package com.bugzero.rarego.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bugzero.rarego.domain.PaymentSagaExecution;
import com.bugzero.rarego.domain.PaymentSagaExecutionStatus;
import com.bugzero.rarego.domain.PaymentSagaType;

import jakarta.persistence.LockModeType;

public interface PaymentSagaExecutionRepository extends JpaRepository<PaymentSagaExecution, Long> {
	Optional<PaymentSagaExecution> findBySagaTypeAndBusinessKey(PaymentSagaType sagaType, String businessKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		SELECT s
		FROM PaymentSagaExecution s
		WHERE s.sagaType = :sagaType
		AND s.businessKey = :businessKey
		""")
	Optional<PaymentSagaExecution> findBySagaTypeAndBusinessKeyForUpdate(
		@Param("sagaType") PaymentSagaType sagaType,
		@Param("businessKey") String businessKey);

	@Query(value = """
		SELECT * FROM payment_saga_execution
		WHERE saga_type = :#{#sagaType.name()}
		AND status = :#{#status.name()}
		AND retryable = 1
		AND next_retry_at IS NOT NULL
		AND next_retry_at <= :now
		ORDER BY next_retry_at ASC
		LIMIT :limit
		""", nativeQuery = true)
	List<PaymentSagaExecution> findRetryTargetsForBatch(
		@Param("sagaType") PaymentSagaType sagaType,
		@Param("status") PaymentSagaExecutionStatus status,
		@Param("now") LocalDateTime now,
		@Param("limit") int limit);

	@Query(value = """
		SELECT * FROM payment_saga_execution
		WHERE saga_type = :#{#sagaType.name()}
		AND status = :#{#status.name()}
		AND last_attempt_at IS NOT NULL
		AND last_attempt_at <= :staleBefore
		ORDER BY last_attempt_at ASC
		LIMIT :limit
		""", nativeQuery = true)
	List<PaymentSagaExecution> findStaleInProgressTargetsForBatch(
		@Param("sagaType") PaymentSagaType sagaType,
		@Param("status") PaymentSagaExecutionStatus status,
		@Param("staleBefore") LocalDateTime staleBefore,
		@Param("limit") int limit);
}
