package com.bugzero.rarego.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.bugzero.rarego.config.PaymentMetrics;
import com.bugzero.rarego.domain.Payment;
import com.bugzero.rarego.domain.PaymentStatus;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.in.dto.TossPaymentsResponseDto;
import com.bugzero.rarego.out.PaymentRepository;
import com.bugzero.rarego.out.TossPaymentsApiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconcilePaymentUseCase {
	private final PaymentRepository paymentRepository;
	private final TossPaymentsApiClient tossPaymentsApiClient;
	private final PaymentMetrics paymentMetrics;

	public void reconcilePayments() {
		LocalDateTime end = LocalDateTime.now().minusMinutes(30);
		LocalDateTime start = LocalDateTime.now().minusDays(1);

		// 30분 ~ 1일 사이의 pending 결제 조회
		List<Payment> payments = paymentRepository.findAllByStatusAndCreatedAtBetween(PaymentStatus.PENDING, start,
			end);
		paymentMetrics.setReconcileBacklog(payments.size());

		if (payments.isEmpty()) {
			return;
		}
		paymentMetrics.recordReconcileTargets(payments.size());

		log.info("보정 PENDING 결제: {}건", payments.size());

		int remainingBacklog = payments.size();
		for (Payment payment : payments) {
			try {
				reconcilePayment(payment);
				remainingBacklog--;
			} catch (Exception e) {
				paymentMetrics.incrementReconcileFailure();
				// 개별 건 실패 시 로그만 남기고 다음 건 진행
				log.error("결제 복구 실패 - orderId: {}", payment.getOrderId(), e);
			} finally {
				paymentMetrics.setReconcileBacklog(remainingBacklog);
			}
		}
	}

	private void reconcilePayment(Payment payment) {
		TossPaymentsResponseDto response;

		try {
			response = tossPaymentsApiClient.getPaymentByOrderId(payment.getOrderId());
		} catch (CustomException e) {
			if (e.getErrorType() == ErrorType.PAYMENT_NOT_FOUND_IN_TOSS) {
				log.info("결제 내역 없음 (단순 이탈) - orderId: {}", payment.getOrderId());
				paymentMetrics.incrementReconcileNotFound();
				markAsFailed(payment);
				return;
			}

			throw e;
		}

		String status = response.status();

		if ("DONE".equals(status) || "WAITING_FOR_DEPOSIT".equals(status)) {
			log.warn("타임아웃된 결제 취소 처리 (Status: {}) - {}", status, payment.getOrderId());

			tossPaymentsApiClient.cancel(response.paymentKey(), "타임아웃 자동 취소");
			paymentMetrics.incrementReconcileCancelRequested();
			markAsFailed(payment);
			return;
		}

		paymentMetrics.incrementReconcileNoAction();
		markAsFailed(payment);
	}

	private void markAsFailed(Payment payment) {
		payment.fail();
		paymentRepository.save(payment);
	}
}
