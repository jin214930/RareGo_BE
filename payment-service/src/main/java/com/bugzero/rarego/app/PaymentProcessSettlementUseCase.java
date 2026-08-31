package com.bugzero.rarego.app;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentProcessSettlementUseCase {
	private final SettlementRepository settlementRepository;
	private final SettlementPayoutRepository payoutRepository;

	@Transactional
	public void prepareSettlements(Long runId, List<? extends Long> settlementIds) {
		if (runId == null) {
			throw new IllegalArgumentException("정산 실행 ID가 필요합니다.");
		}
		if (settlementIds == null || settlementIds.isEmpty()) {
			return;
		}
		// 겹치는 실행도 같은 순서로 UPDATE하여 교착 가능성을 줄인다.
		for (Long id : settlementIds.stream().distinct().sorted().toList()) {
			if (settlementRepository.markPendingIfReady(id) == 0) {
				continue;
			}
			Settlement settlement = settlementRepository.findById(id).orElseThrow();
			payoutRepository.save(SettlementPayout.builder()
				.runId(runId)
				.settlement(settlement)
				.build());
		}
	}
}
