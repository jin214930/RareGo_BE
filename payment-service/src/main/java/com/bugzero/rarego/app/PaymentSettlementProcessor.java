package com.bugzero.rarego.app;

import java.util.ArrayList;
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
import com.bugzero.rarego.out.SettlementRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentSettlementProcessor {
	private final PaymentSupport paymentSupport;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final SettlementFeeRepository settlementFeeRepository;
	private final SettlementRepository settlementRepository;

	@Value("${custom.payment.systemMemberId}")
	private Long systemMemberId;

	@Transactional
	public void processSellerDeposits(Long sellerId, List<Settlement> settlements) {
		if (settlements == null || settlements.isEmpty()) {
			return;
		}

		Wallet sellerWallet = paymentSupport.findWalletByMemberIdForUpdate(sellerId);

		int runningBalance = sellerWallet.getBalance();
		int totalSettlementAmount = 0;
		List<SettlementFee> fees = new ArrayList<>();

		for (Settlement settlement : settlements) {
			int amount = settlement.getSettlementAmount();

			runningBalance += amount;
			totalSettlementAmount += amount;

			saveSettlementTransaction(
				sellerWallet,
				WalletTransactionType.SETTLEMENT_PAID,
				amount,
				runningBalance,
				settlement.getId()
			);

			settlement.complete();

			SettlementFee fee = SettlementFee.builder()
				.settlement(settlement)
				.feeAmount(settlement.getFeeAmount())
				.build();
			fees.add(fee);
		}

		if (totalSettlementAmount > 0) {
			sellerWallet.addBalance(totalSettlementAmount);
		}

		settlementFeeRepository.saveAll(fees);
		settlementRepository.saveAll(settlements);
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
				systemWallet.getBalance(),
				0L // 여러 건 합산이므로 ID 0
			);
		}

		// 4. 처리된 수수료 데이터 일괄 삭제
		settlementFeeRepository.deleteAllInBatch(fees);

		return fees.size();
	}

	private void saveSettlementTransaction(Wallet wallet, WalletTransactionType type, int amount, int balanceAfter, Long settlementId) {
		PaymentTransaction transaction = PaymentTransaction.builder()
			.wallet(wallet)
			.member(wallet.getMember())
			.transactionType(type)
			.balanceDelta(amount)
			.holdingDelta(0)
			.balanceAfter(balanceAfter)
			.referenceType(ReferenceType.SETTLEMENT)
			.referenceId(settlementId)
			.build();

		paymentTransactionRepository.save(transaction);
	}
}
