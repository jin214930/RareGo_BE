package com.bugzero.rarego.in;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.app.PaymentSettlementProcessor;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {
	private final PaymentFacade paymentFacade;
	private final PaymentSettlementProcessor paymentSettlementProcessor;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleSettlementFinished(SettlementFinishedEvent event) {
		try {
			paymentSettlementProcessor.processFees(1000);
		} catch (Exception e) {
			log.error("수수료 징수 중 에러 발생 (다음 배치에서 처리됨)", e);
		}
	}
}
