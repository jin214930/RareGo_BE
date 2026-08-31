package com.bugzero.rarego.domain;

import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "PAYMENT_SETTLEMENT_PAYOUT", uniqueConstraints = {
	@UniqueConstraint(name = "uk_payout_run_chunk_recipient", columnNames = {"run_id", "chunk_id", "recipient_id"})
}, indexes = {
	@Index(name = "idx_payout_run_paid_recipient", columnList = "run_id, paid, recipient_id")
})
public class SettlementPayout extends BaseIdAndTime {
	@Column(name = "run_id", nullable = false)
	private Long runId;

	@Column(name = "chunk_id", nullable = false)
	private Long chunkId;

	@Column(name = "recipient_id", nullable = false)
	private Long recipientId;

	@Column(nullable = false)
	private long amount;

	@Column(name = "source_count", nullable = false)
	private int sourceCount;

	@Column(nullable = false)
	private boolean paid;

	@Builder
	protected SettlementPayout(Long runId, Long chunkId, Long recipientId, long amount, int sourceCount) {
		if (runId == null || chunkId == null || recipientId == null || amount < 0 || sourceCount <= 0) {
			throw new IllegalArgumentException("부분합은 실행/청크/수취인 ID와 비음수 금액, 양수 원천 건수가 필요합니다.");
		}
		this.runId = runId;
		this.chunkId = chunkId;
		this.recipientId = recipientId;
		this.amount = amount;
		this.sourceCount = sourceCount;
	}

	public void complete() {
		if (paid) {
			throw new IllegalStateException("지급 대기 상태가 아닌 정산은 입금할 수 없습니다.");
		}
		paid = true;
	}
}
