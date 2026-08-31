package com.bugzero.rarego.app;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.ReferenceType;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementType;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentSettlementProcessor {
	private final PaymentSupport paymentSupport;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final SettlementRepository settlementRepository;

	@Transactional
	public void processRecipientDeposits(Long recipientId, List<Settlement> settlements) {
		if (settlements == null || settlements.isEmpty()) {
			return;
		}

		Wallet recipientWallet = paymentSupport.findWalletByMemberIdForUpdate(recipientId);

		int runningBalance = recipientWallet.getBalance();
		int totalSettlementAmount = 0;

		for (Settlement settlement : settlements) {
			int amount = settlement.getSettlementAmount();

			runningBalance += amount;
			totalSettlementAmount += amount;

			saveSettlementTransaction(
				recipientWallet,
				settlement.getType() == SettlementType.PLATFORM_FEE
					? WalletTransactionType.SETTLEMENT_FEE : WalletTransactionType.SETTLEMENT_PAID,
				amount,
				runningBalance,
				settlement.getId()
			);

			settlement.complete();
		}

		if (totalSettlementAmount > 0) {
			recipientWallet.addBalance(totalSettlementAmount);
		}

		settlementRepository.saveAll(settlements);
	}

	private void saveSettlementTransaction(Wallet wallet, WalletTransactionType type, int amount, int balanceAfter,
		Long settlementId) {
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
