package com.bugzero.rarego.app;

import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentProcessSettlementUseCase {
	private final SettlementRepository settlementRepository;
	private final SettlementPayoutRepository payoutRepository;
	private final EntityManager entityManager;

	@Transactional
	public void prepareSettlements(Long runId, List<? extends Long> settlementIds) {
		if (runId == null) {
			throw new IllegalArgumentException("정산 실행 ID가 필요합니다.");
		}
		if (settlementIds == null || settlementIds.isEmpty()) {
			return;
		}
		List<Settlement> sources = settlementRepository.findReadyForUpdate(
			settlementIds.stream().<Long>map(id -> id).distinct().sorted().toList());
		if (sources.isEmpty()) {
			return;
		}
		Long chunkId = sources.getFirst().getId();
		var recipients = sources.stream().collect(Collectors.groupingBy(
			source -> source.getRecipient().getId(), TreeMap::new, Collectors.toList()));
		for (var entry : recipients.entrySet()) {
			long amount = 0;
			for (Settlement source : entry.getValue()) {
				if (source.getSettlementAmount() < 0) {
					throw new IllegalArgumentException("정산 원천 금액은 음수일 수 없습니다.");
				}
				amount = Math.addExact(amount, source.getSettlementAmount());
			}
			SettlementPayout payout = payoutRepository.save(SettlementPayout.builder()
				.runId(runId)
				.chunkId(chunkId)
				.recipientId(entry.getKey())
				.amount(amount)
				.sourceCount(entry.getValue().size())
				.build());
			List<Long> ids = entry.getValue().stream().map(Settlement::getId).toList();
			if (settlementRepository.assignPayout(ids, payout) != ids.size()) {
				throw new IllegalStateException("부분합에 연결한 원천 건수가 일치하지 않습니다.");
			}
		}
		// 벌크 변경으로 오래된 원천 상태가 영속성 컨텍스트에 남지 않게 한다.
		entityManager.flush();
		entityManager.clear();
	}
}
