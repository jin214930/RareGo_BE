package com.bugzero.rarego.out;

import java.util.List;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class SettlementBulkRepository {
	private final EntityManager entityManager;

	public int insertTransactions(List<Long> payoutIds, Long walletId, Long recipientId, int openingBalance) {
		entityManager.flush();
		return entityManager.createNativeQuery("""
			INSERT INTO payment_transaction
			(wallet_id, member_id, transaction_type, balance_delta, holding_delta, balance_after,
				reference_type, reference_id, deleted, created_at, updated_at)
			SELECT :walletId, s.recipient_id,
				CASE WHEN s.type = 'PLATFORM_FEE' THEN 'SETTLEMENT_FEE' ELSE 'SETTLEMENT_PAID' END,
				s.settlement_amount, 0,
				:openingBalance + SUM(s.settlement_amount) OVER (
					ORDER BY s.id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW),
				'SETTLEMENT', s.id, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
			FROM payment_settlement s
			WHERE s.payout_id IN (:payoutIds) AND s.recipient_id = :recipientId
				AND s.status = 'PENDING' AND s.settlement_amount >= 0
			ORDER BY s.id
			""")
			.setParameter("walletId", walletId)
			.setParameter("recipientId", recipientId)
			.setParameter("openingBalance", openingBalance)
			.setParameter("payoutIds", payoutIds)
			.executeUpdate();
	}
}
