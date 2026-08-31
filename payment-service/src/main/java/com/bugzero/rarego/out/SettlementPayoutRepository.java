package com.bugzero.rarego.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.bugzero.rarego.domain.SettlementPayout;

import jakarta.persistence.LockModeType;

public interface SettlementPayoutRepository extends JpaRepository<SettlementPayout, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		SELECT p FROM SettlementPayout p
		WHERE p.runId = :runId AND p.recipientId = :recipientId AND p.paid = false
		ORDER BY p.id
		""")
	List<SettlementPayout> findPendingForUpdate(Long runId, Long recipientId);
}
