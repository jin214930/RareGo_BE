package com.bugzero.rarego.domain;

import java.util.List;

import com.bugzero.rarego.global.jpa.entity.BaseIdAndTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Builder
@Getter
@Table(name = "PAYMENT_SETTLEMENT", uniqueConstraints = {
	@UniqueConstraint(name = "uk_settlement_auction_type", columnNames = {"auction_id", "type"})
}, indexes = {
	@Index(name = "idx_settlement_status_created_id", columnList = "status, created_at, id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseIdAndTime {
	private static final double FEE_RATE = 0.1; // 10% 수수료

	private static final int MAX_TRY_COUNT = 3;

	@Column(name = "auction_id", nullable = false)
	private Long auctionId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(nullable = false)
	// 시스템 수수료 원천에서도 원거래 판매자 정보를 유지한다.
	private PaymentMember seller;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "recipient_id", nullable = false)
	// 실제 지급 대상: 판매자 또는 시스템 회원.
	private PaymentMember recipient;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Builder.Default
	private SettlementType type = SettlementType.SELLER_PROCEEDS;

	@Column(nullable = false)
	private int salesAmount;

	@Column(nullable = false)
	private int feeAmount;

	@Column(nullable = false)
	private int settlementAmount;

	@Column(nullable = false)
	private String productName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Builder.Default
	private SettlementStatus status = SettlementStatus.READY;

	@Builder.Default
	@Column(nullable = false)
	private int tryCount = 0;

	public static Settlement create(Long auctionId, String productName, PaymentMember seller, int salesAmount) {
		validateAmount(salesAmount);
		int feeAmount = (int)(salesAmount * FEE_RATE);
		int settlementAmount = salesAmount - feeAmount;

		return Settlement.builder()
			.auctionId(auctionId)
			.seller(seller)
			.recipient(seller)
			.type(SettlementType.SELLER_PROCEEDS)
			.salesAmount(salesAmount)
			.feeAmount(feeAmount)
			.settlementAmount(settlementAmount)
			.productName(productName)
			.status(SettlementStatus.READY)
			.build();
	}

	public static List<Settlement> createPaymentSources(Long auctionId, String productName, PaymentMember seller,
		PaymentMember systemMember, int salesAmount) {
		Settlement proceeds = create(auctionId, productName, seller, salesAmount);
		Settlement fee = Settlement.builder()
			.auctionId(auctionId)
			.seller(seller)
			.recipient(systemMember)
			.type(SettlementType.PLATFORM_FEE)
			.salesAmount(salesAmount)
			.feeAmount(proceeds.getFeeAmount())
			.settlementAmount(proceeds.getFeeAmount())
			.productName(productName)
			.build();
		return List.of(proceeds, fee);
	}

	public static Settlement createFromForfeit(Long auctionId, String productName, PaymentMember seller,
		int forfeitAmount) {
		validateAmount(forfeitAmount);
		return Settlement.builder()
			.auctionId(auctionId)
			.seller(seller)
			.recipient(seller)
			.type(SettlementType.DEPOSIT_FORFEIT)
			.salesAmount(forfeitAmount)
			.feeAmount(0)
			.settlementAmount(forfeitAmount)
			.productName(productName)
			.status(SettlementStatus.READY)
			.build();
	}

	private static void validateAmount(int amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("정산 원천 금액은 음수일 수 없습니다.");
		}
	}

	public void complete() {
		this.status = SettlementStatus.DONE;
	}

	// 정산 중 실패 처리
	public boolean fail() {
		this.tryCount++;

		// 3번 시도해도 실패하면 FAILED로 변경
		if (this.tryCount > MAX_TRY_COUNT) {
			this.status = SettlementStatus.FAILED;
			return true; // 수동 처리 대상
		}

		// 재처리 대상 다음 배치 때 재시도
		return false;
	}

	// 환불 시 실패 처리
	public void cancel() {
		this.status = SettlementStatus.CANCELED;
	}
}
