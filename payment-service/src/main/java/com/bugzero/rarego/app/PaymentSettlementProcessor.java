package com.bugzero.rarego.app;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.SettlementBulkRepository;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.shared.payment.dto.SettlementResponseDto;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentSettlementProcessor {
	private static final int EVENT_BATCH_SIZE = 100;
	private final PaymentSupport paymentSupport;
	private final SettlementBulkRepository bulkRepository;
	private final SettlementRepository settlementRepository;
	private final SettlementPayoutRepository payoutRepository;
	private final OutboxUseCase outboxUseCase;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void processRecipientDeposits(Long runId, Long recipientId) {
		Wallet wallet = paymentSupport.findWalletByMemberIdForUpdate(recipientId);
		// 잠금 조회로 다른 실행이 지급한 최신 상태를 확인한다.
		List<SettlementPayout> payouts = payoutRepository.findPendingForUpdate(runId, recipientId);
		if (payouts.isEmpty()) {
			return;
		}
		long total = 0;
		int sourceCount = 0;
		for (SettlementPayout payout : payouts) {
			total = Math.addExact(total, payout.getAmount());
			sourceCount = Math.addExact(sourceCount, payout.getSourceCount());
		}
		int amount = Math.toIntExact(total);
		Math.addExact(wallet.getBalance(), amount);
		List<Long> payoutIds = payouts.stream().map(SettlementPayout::getId).toList();
		// 잠금 조회한 부분합에 속한 원천만 처리한다. 늦게 생성된 부분합은 섞지 않는다.
		if (bulkRepository.insertTransactions(payoutIds, wallet.getId(), recipientId, wallet.getBalance())
			!= sourceCount) {
			throw new IllegalStateException("원장에 기록한 정산 원천 건수가 일치하지 않습니다.");
		}
		if (settlementRepository.completeSources(payoutIds, recipientId) != sourceCount) {
			throw new IllegalStateException("완료 처리한 정산 원천 건수가 일치하지 않습니다.");
		}
		if (amount > 0) {
			wallet.addBalance(amount);
		}
		payouts.forEach(SettlementPayout::complete);
		publishCompletedSources(payoutIds);
	}

	private void publishCompletedSources(List<Long> payoutIds) {
		long afterId = 0;
		while (true) {
			List<SettlementResponseDto> responses = settlementRepository.findCompletedResponses(
				payoutIds, afterId, PageRequest.of(0, EVENT_BATCH_SIZE));
			if (responses.isEmpty()) {
				return;
			}
			outboxUseCase.saveOutbox(SettlementFinishedEvent.of(responses));
			if (responses.size() < EVENT_BATCH_SIZE) {
				return;
			}
			afterId = responses.getLast().id();
		}
	}
}
