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
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.shared.payment.dto.SettlementResponseDto;

import jakarta.persistence.LockModeType;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
	// 수취인 JOIN FETCH는 회원 행까지 잠글 수 있으므로 원천만 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		SELECT s FROM Settlement s WHERE s.id IN :ids
		AND s.status = com.bugzero.rarego.domain.SettlementStatus.READY AND s.payout IS NULL
		ORDER BY s.id
		""")
	List<Settlement> findReadyForUpdate(List<Long> ids);

	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE Settlement s SET s.status = com.bugzero.rarego.domain.SettlementStatus.PENDING,
		s.payout = :payout, s.updatedAt = CURRENT_TIMESTAMP
		WHERE s.id IN :ids AND s.status = com.bugzero.rarego.domain.SettlementStatus.READY AND s.payout IS NULL
		""")
	int assignPayout(List<Long> ids, SettlementPayout payout);

	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE Settlement s SET s.status = com.bugzero.rarego.domain.SettlementStatus.DONE,
		s.updatedAt = CURRENT_TIMESTAMP
		WHERE s.payout.id IN :payoutIds AND s.recipient.id = :recipientId
		AND s.status = com.bugzero.rarego.domain.SettlementStatus.PENDING
		""")
	int completeSources(List<Long> payoutIds, Long recipientId);

	@Query("""
		SELECT new com.bugzero.rarego.shared.payment.dto.SettlementResponseDto(
			s.id, s.auctionId, s.seller.id, s.salesAmount, s.feeAmount, s.settlementAmount,
			s.productName, 'DONE', s.createdAt)
		FROM Settlement s WHERE s.payout.id IN :payoutIds AND s.id > :afterId
		AND s.status = com.bugzero.rarego.domain.SettlementStatus.DONE
		AND s.type <> com.bugzero.rarego.domain.SettlementType.PLATFORM_FEE
		ORDER BY s.id
		""")
	List<SettlementResponseDto> findCompletedResponses(List<Long> payoutIds, long afterId, Pageable pageable);

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
