package com.bugzero.rarego.app;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.shared.payment.dto.SettlementResponseDto;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProcessSettlementUseCase {
	private final PaymentSettlementProcessor paymentSettlementProcessor;
	private final OutboxUseCase outboxUseCase;

	@Transactional
	public void processSettlements(List<? extends Settlement> settlements) {
		if (settlements == null || settlements.isEmpty()) {
			return;
		}

		Map<Long, List<Settlement>> settlementsBySeller = settlements.stream()
			.collect(Collectors.groupingBy(s -> s.getSeller().getId()));

		settlementsBySeller.forEach(paymentSettlementProcessor::processSellerDeposits);

		List<SettlementResponseDto> responses = settlements.stream()
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

		outboxUseCase.saveOutbox(SettlementFinishedEvent.of(responses));
	}
}
