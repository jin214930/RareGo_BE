package com.bugzero.rarego.app;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.ReferenceType;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementFee;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementFeeRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentSettlementProcessor {
	private final PaymentSupport paymentSupport;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final SettlementFeeRepository settlementFeeRepository;

	@Value("${custom.payment.systemMemberId}")
	private Long systemMemberId;

	@Transactional
	public void processSellerDeposits(Long sellerId, List<Settlement> settlements) {
		if (settlements == null || settlements.isEmpty()) {
			return;
		}

		Wallet sellerWallet = paymentSupport.findWalletByMemberIdForUpdate(sellerId);

		int totalSettlementAmount = settlements.stream()
			.mapToInt(Settlement::getSettlementAmount)
			.sum();

		if (totalSettlementAmount > 0) {
			sellerWallet.addBalance(totalSettlementAmount);
		}

		for (Settlement settlement : settlements) {
			saveSettlementTransaction(
				sellerWallet,
				WalletTransactionType.SETTLEMENT_PAID,
				settlement.getSettlementAmount(),
				settlement.getId()
			);

			settlement.complete();

			SettlementFee fee = SettlementFee.builder()
				.settlement(settlement)
				.feeAmount(settlement.getFeeAmount())
				.build();
			settlementFeeRepository.save(fee);
		}
	}

	@Transactional
	public int processFees() {
		// 1. 수수료 테이블 데이터 조회
		List<SettlementFee> fees = settlementFeeRepository.findTop1000ByOrderByIdAsc();

		if (fees.isEmpty()) {
			return 0;
		}

		// 2. 금액 합산
		int totalFeeAmount = fees.stream()
			.mapToInt(SettlementFee::getFeeAmount)
			.sum();

		// 3. 시스템 지갑 입금
		if (totalFeeAmount > 0) {
			Wallet systemWallet = paymentSupport.findWalletByMemberIdForUpdate(systemMemberId);
			systemWallet.addBalance(totalFeeAmount);

			saveSettlementTransaction(
				systemWallet,
				WalletTransactionType.SETTLEMENT_FEE,
				totalFeeAmount,
				0L // 여러 건 합산이므로 ID 0
			);
		}

		// 4. 처리된 수수료 데이터 일괄 삭제
		settlementFeeRepository.deleteAllInBatch(fees);

		return fees.size();
	}

	private void saveSettlementTransaction(Wallet wallet, WalletTransactionType type, int amount, Long settlementId) {
		PaymentTransaction transaction = PaymentTransaction.builder()
			.wallet(wallet)
			.member(wallet.getMember())
			.transactionType(type)
			.balanceDelta(amount)
			.holdingDelta(0)
			.balanceAfter(wallet.getBalance())
			.referenceType(ReferenceType.SETTLEMENT)
			.referenceId(settlementId)
			.build();

		paymentTransactionRepository.save(transaction);
	}
}
