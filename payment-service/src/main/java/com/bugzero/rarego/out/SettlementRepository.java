package com.bugzero.rarego.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementStatus;

import jakarta.persistence.LockModeType;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Settlement s SET s.status = com.bugzero.rarego.domain.SettlementStatus.PENDING
		WHERE s.id = :id AND s.status = com.bugzero.rarego.domain.SettlementStatus.READY
		""")
	int markPendingIfReady(Long id);

	@Query("""
		SELECT COALESCE(MIN(s.id), 1) AS minId, COALESCE(MAX(s.id), 0) AS maxId
		FROM Settlement s WHERE s.status = com.bugzero.rarego.domain.SettlementStatus.READY
		AND s.createdAt < :cutoff
		""")
	IdBounds findReadyBounds(LocalDateTime cutoff);

	interface IdBounds {
		Long getMinId();

		Long getMaxId();
	}

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM Settlement s WHERE s.auctionId = :auctionId")
	Optional<Settlement> findByAuctionIdForUpdate(Long auctionId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM Settlement s WHERE s.auctionId = :auctionId ORDER BY s.id")
	List<Settlement> findAllByAuctionIdForUpdate(Long auctionId);

	List<Settlement> findAllByStatus(SettlementStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		SELECT s
		FROM Settlement s
		JOIN FETCH s.seller
		WHERE s.id = :id
		""")
	Optional<Settlement> findByIdForUpdate(Long id);

	@Query("""
		SELECT s FROM Settlement s
		WHERE s.seller.id = :sellerId
		AND s.type <> com.bugzero.rarego.domain.SettlementType.PLATFORM_FEE
		AND (:status IS NULL OR s.status = :status)
		AND (:from IS NULL OR s.createdAt >= :from)
		AND (:to IS NULL OR s.createdAt < :to)
		""")
	Page<Settlement> searchSettlements(Long sellerId, SettlementStatus status, LocalDateTime from,
		LocalDateTime to, Pageable pageable);
}
