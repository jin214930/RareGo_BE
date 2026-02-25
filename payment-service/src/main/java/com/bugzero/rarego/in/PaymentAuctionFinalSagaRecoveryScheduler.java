package com.bugzero.rarego.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.app.PaymentAuctionFinalSagaRecoveryUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAuctionFinalSagaRecoveryScheduler {
	private final PaymentAuctionFinalSagaRecoveryUseCase recoveryUseCase;

	@Scheduled(cron = "${payment.saga.auction-final-recovery.cron:0 */10 * * * *}")
	public void recoverFailedFinalPayments() {
		try {
			recoveryUseCase.recoverFailedFinalPayments();
		} catch (Exception e) {
			log.error("낙찰 최종결제 Saga 재개 스케줄러 실행 실패: {}", e.getMessage());
		}
	}
}
