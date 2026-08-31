package com.bugzero.rarego.app;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.ReferenceType;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.SettlementType;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.shared.payment.dto.SettlementResponseDto;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentSettlementProcessor {
	private static final int EVENT_BATCH_SIZE = 100;
	private final PaymentSupport paymentSupport;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final SettlementPayoutRepository payoutRepository;
	private final OutboxUseCase outboxUseCase;

	@Transactional
	public void processRecipientDeposits(Long runId, Long recipientId) {
		Wallet wallet = paymentSupport.findWalletByMemberIdForUpdate(recipientId);
		// 잠금 조회로 다른 실행이 지급한 최신 상태를 확인한다.
		List<SettlementPayout> payouts = payoutRepository.findPendingForUpdate(runId, recipientId);
		if (payouts.isEmpty()) {
			return;
		}
		int amount = Math.toIntExact(payouts.stream().mapToLong(SettlementPayout::getAmount).sum());
		Math.addExact(wallet.getBalance(), amount);
		int runningBalance = wallet.getBalance();
		if (amount > 0) {
			wallet.addBalance(amount);
		}

		List<SettlementResponseDto> responses = new ArrayList<>();
		for (SettlementPayout payout : payouts) {
			Settlement settlement = payout.getSettlement();
			runningBalance = Math.addExact(runningBalance, payout.getAmount());
			saveTransaction(wallet, settlement, payout.getAmount(), runningBalance);
			payout.complete();
			if (settlement.getType() != SettlementType.PLATFORM_FEE) {
				responses.add(toResponse(settlement));
			}
			if (responses.size() == EVENT_BATCH_SIZE) {
				publish(responses);
				responses.clear();
			}
		}
		publish(responses);
	}

	private void publish(List<SettlementResponseDto> responses) {
		if (!responses.isEmpty()) {
			outboxUseCase.saveOutbox(SettlementFinishedEvent.of(List.copyOf(responses)));
		}
	}

	private SettlementResponseDto toResponse(Settlement source) {
		return new SettlementResponseDto(source.getId(), source.getAuctionId(), source.getSeller().getId(),
			source.getSalesAmount(), source.getFeeAmount(), source.getSettlementAmount(), source.getProductName(),
			source.getStatus().name(), source.getCreatedAt());
	}

	private void saveTransaction(Wallet wallet, Settlement settlement, int amount, int balanceAfter) {
		paymentTransactionRepository.save(PaymentTransaction.builder()
			.wallet(wallet)
			.member(wallet.getMember())
			.transactionType(settlement.getType() == SettlementType.PLATFORM_FEE
				? WalletTransactionType.SETTLEMENT_FEE : WalletTransactionType.SETTLEMENT_PAID)
			.balanceDelta(amount)
			.holdingDelta(0)
			.balanceAfter(balanceAfter)
			.referenceType(ReferenceType.SETTLEMENT)
			.referenceId(settlement.getId())
			.build());
	}
}
