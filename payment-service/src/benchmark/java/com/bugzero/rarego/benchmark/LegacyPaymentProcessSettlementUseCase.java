package com.bugzero.rarego.benchmark;

// 기준선: 36e3b8e9. package/class/bean 이름 및 Facade 위임만 변경.

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementType;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.shared.payment.dto.SettlementResponseDto;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyPaymentProcessSettlementUseCase {
	private final LegacyPaymentSettlementProcessor paymentSettlementProcessor;
	private final OutboxUseCase outboxUseCase;

	@Transactional
	public void processSettlements(List<? extends Settlement> settlements) {
		if (settlements == null || settlements.isEmpty()) {
			return;
		}

		// 공통 시스템 지갑을 포함해 청크 간 지갑 락 획득 순서를 동일하게 유지한다.
		Map<Long, List<Settlement>> settlementsByRecipient = settlements.stream()
			.collect(Collectors.groupingBy(s -> s.getRecipient().getId(), TreeMap::new, Collectors.toList()));

		settlementsByRecipient.forEach(paymentSettlementProcessor::processRecipientDeposits);

		List<SettlementResponseDto> responses = settlements.stream()
			.filter(s -> s.getType() != SettlementType.PLATFORM_FEE)
			.map(s -> new SettlementResponseDto(
				s.getId(),
				s.getAuctionId(),
				s.getSeller().getId(),
				s.getSalesAmount(),
				s.getFeeAmount(),
				s.getSettlementAmount(),
				s.getProductName(),
				s.getStatus().name(),
				s.getCreatedAt()
			))
			.toList();

		if (!responses.isEmpty()) {
			outboxUseCase.saveOutbox(SettlementFinishedEvent.of(responses));
		}
	}
}
