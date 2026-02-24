package com.bugzero.rarego.config;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;

@Component
@Getter
public class PaymentMetrics {

	private final Counter paymentTotalCounter;
	private final Counter paymentSuccessCounter;
	private final Counter paymentFailPgRejectCounter;
	private final Counter paymentFailValidationCounter;
	private final Counter paymentFailSystemCounter;
	private final Counter paymentRefundCounter;
	private final DistributionSummary paymentAmountSummary;
	private final MeterRegistry registry;
	private final AtomicInteger reconcileBacklogGauge;

	public PaymentMetrics(MeterRegistry registry) {
		this.registry = registry;
		// 결제 시도 총 횟수
		this.paymentTotalCounter = Counter.builder("payment.total")
			.description("Total payment attempts")
			.register(registry);

		// 결제 성공 횟수
		this.paymentSuccessCounter = Counter.builder("payment.success")
			.description("Successful payments")
			.register(registry);

		// 결제 실패 - PG 거절
		this.paymentFailPgRejectCounter = Counter.builder("payment.fail")
			.tag("reason", "pg_reject")
			.description("Failed payments - PG rejection")
			.register(registry);

		// 결제 실패 - 검증 실패
		this.paymentFailValidationCounter = Counter.builder("payment.fail")
			.tag("reason", "validation")
			.description("Failed payments - validation error")
			.register(registry);

		// 결제 실패 - 시스템 에러
		this.paymentFailSystemCounter = Counter.builder("payment.fail")
			.tag("reason", "system_error")
			.description("Failed payments - system error")
			.register(registry);

		// 환불 처리 횟수
		this.paymentRefundCounter = Counter.builder("payment.refund")
			.description("Payment refunds processed")
			.register(registry);

		// 결제 금액 분포
		this.paymentAmountSummary = DistributionSummary.builder("payment.amount")
			.description("Payment amount distribution")
			.baseUnit("won")
			.publishPercentiles(0.5, 0.75, 0.95, 0.99)
			.register(registry);

		this.reconcileBacklogGauge = new AtomicInteger();
		Gauge.builder("payment.reconcile.backlog", reconcileBacklogGauge,
				AtomicInteger::get)
			.description("Current pending payments targeted for reconcile job")
			.register(registry);
	}

	public void incrementPaymentTotal() {
		paymentTotalCounter.increment();
	}

	public void incrementPaymentSuccess() {
		paymentSuccessCounter.increment();
	}

	public void incrementPaymentFailPgReject() {
		paymentFailPgRejectCounter.increment();
	}

	public void incrementPaymentFailValidation() {
		paymentFailValidationCounter.increment();
	}

	public void incrementPaymentFailSystem() {
		paymentFailSystemCounter.increment();
	}

	public void incrementRefund() {
		paymentRefundCounter.increment();
	}

	public void recordPaymentAmount(long amount) {
		paymentAmountSummary.record(amount);
	}

	public void setReconcileBacklog(int backlog) {
		reconcileBacklogGauge.set(Math.max(backlog, 0));
	}

	public void recordReconcileTargets(int count) {
		if (count > 0) {
			registry.counter("payment.reconcile.target.total").increment(count);
		}
	}

	public void incrementReconcileNoAction() {
		registry.counter("payment.reconcile.process.total", "result", "no_action").increment();
	}

	public void incrementReconcileFailure() {
		registry.counter("payment.reconcile.process.total", "result", "failed").increment();
	}

	public void incrementReconcileNotFound() {
		registry.counter("payment.reconcile.process.total", "result", "not_found_in_pg").increment();
	}

	public void incrementReconcileCancelRequested() {
		registry.counter("payment.reconcile.process.total", "result", "cancel_requested").increment();
	}

	public void incrementCompensationCancelSuccess() {
		registry.counter("payment.compensation.cancel.total", "result", "success").increment();
	}

	public void incrementCompensationCancelFailure() {
		registry.counter("payment.compensation.cancel.total", "result", "failed").increment();
	}
}
