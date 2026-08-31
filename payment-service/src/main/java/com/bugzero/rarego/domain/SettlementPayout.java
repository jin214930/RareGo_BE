package com.bugzero.rarego.domain;

import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "PAYMENT_SETTLEMENT_PAYOUT", indexes = {
	@Index(name = "idx_payout_run_paid_recipient", columnList = "run_id, paid, recipient_id")
})
public class SettlementPayout extends BaseIdAndTime {
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "settlement_id", nullable = false, unique = true)
	private Settlement settlement;

	@Column(name = "run_id", nullable = false)
	private Long runId;

	@Column(name = "recipient_id", nullable = false)
	private Long recipientId;

	@Column(nullable = false)
	private int amount;

	@Column(nullable = false)
	private boolean paid;

	@Builder
	protected SettlementPayout(Long runId, Settlement settlement) {
		if (runId == null || settlement == null || settlement.getStatus() != SettlementStatus.PENDING
			|| settlement.getSettlementAmount() < 0) {
			throw new IllegalArgumentException("지급 대기는 실행 ID와 PENDING 상태의 유효한 정산 원천이 필요합니다.");
		}
		this.runId = runId;
		this.settlement = settlement;
		this.recipientId = settlement.getRecipient().getId();
		this.amount = settlement.getSettlementAmount();
	}

	public void complete() {
		if (paid || settlement.getStatus() != SettlementStatus.PENDING) {
			throw new IllegalStateException("지급 대기 상태가 아닌 정산은 입금할 수 없습니다.");
		}
		paid = true;
		settlement.complete();
	}
}
