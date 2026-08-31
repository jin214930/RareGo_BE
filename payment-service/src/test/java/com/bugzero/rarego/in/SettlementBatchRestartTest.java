package com.bugzero.rarego.in;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.bugzero.rarego.app.PaymentProcessSettlementUseCase;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

@SpringBootTest(properties = {
	"batch.thread.size=1", "custom.payment.settlement.payoutThreadSize=1",
	"custom.payment.settlement.chunkSize=2", "custom.payment.settlement.holdDays=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SettlementBatchRestartTest extends AbstractSettlementTest {
	@MockitoSpyBean
	private PaymentProcessSettlementUseCase preparation;
	@MockitoBean
	private OutboxUseCase outboxUseCase;

	@Test
	void preparationFailureStopsDepositAndRestartKeepsBoundaryAndExistingQueue() throws Exception {
		createTestData(3, 3);
		AtomicInteger calls = new AtomicInteger();
		doAnswer(invocation -> {
			if (calls.incrementAndGet() == 2) {
				throw new IllegalStateException("prepare chunk failure");
			}
			return invocation.callRealMethod();
		}).when(preparation).prepareSettlements(anyLong(), anyList());

		JobExecution failed = jobOperatorTestUtils.startJob();

		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(payoutRepository.count()).isEqualTo(2);
		assertThat(paymentTransactionRepository.count()).isZero();
		assertThat(walletRepository.findAll()).allMatch(w -> w.getBalance() == 0);
		assertThat(failed.getStepExecutions()).noneMatch(s -> s.getStepName().equals("payoutMainStep"));
		List<Settlement> nextRunSources = settlementRepository.saveAll(Settlement.createPaymentSources(
			99999L, "다음 실행 상품", paymentMemberRepository.findById(101L).orElseThrow(),
			paymentMemberRepository.findById(2L).orElseThrow(), 10000));
		reset(preparation);

		JobExecution restarted = jobOperatorTestUtils.getJobOperator().restart(failed);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(restarted.getJobInstanceId()).isEqualTo(failed.getJobInstanceId());
		assertThat(restarted.getExecutionContext().getString("settlementCutoff"))
			.isEqualTo(failed.getExecutionContext().getString("settlementCutoff"));
		assertThat(payoutRepository.findAll()).hasSize(6).allMatch(SettlementPayout::isPaid);
		assertThat(paymentTransactionRepository.count()).isEqualTo(6);
		assertThat(settlementRepository.findAllById(nextRunSources.stream().map(Settlement::getId).toList()))
			.allMatch(s -> s.getStatus() == SettlementStatus.READY);

		JobExecution nextRun = jobOperatorTestUtils.startJob();
		assertThat(nextRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(payoutRepository.findAll()).hasSize(8).allMatch(SettlementPayout::isPaid);
		assertThat(paymentTransactionRepository.count()).isEqualTo(8);
		assertThat(walletRepository.findByMemberId(101L).orElseThrow().getBalance()).isEqualTo(18000);
	}

	@Test
	void payoutRestartDoesNotSkipRemainingRecipientsOrPayCompletedRecipientsAgain() throws Exception {
		createTestData(3, 3);
		doAnswer(invocation -> {
			SettlementFinishedEvent event = invocation.getArgument(0);
			if (event.settlements().stream().anyMatch(s -> s.sellerId() == 102L)) {
				throw new IllegalStateException("recipient outbox failure");
			}
			return null;
		}).when(outboxUseCase).saveOutbox(any(SettlementFinishedEvent.class));

		JobExecution failed = jobOperatorTestUtils.startJob();

		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(walletRepository.findByMemberId(2L).orElseThrow().getBalance()).isEqualTo(3000);
		assertThat(walletRepository.findByMemberId(101L).orElseThrow().getBalance()).isEqualTo(9000);
		assertThat(walletRepository.findByMemberId(102L).orElseThrow().getBalance()).isZero();
		assertThat(paymentTransactionRepository.count()).isEqualTo(4);
		reset(outboxUseCase);

		JobExecution restarted = jobOperatorTestUtils.getJobOperator().restart(failed);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(restarted.getJobInstanceId()).isEqualTo(failed.getJobInstanceId());
		assertThat(payoutRepository.findAll()).hasSize(6)
			.allMatch(p -> p.isPaid() && p.getRunId() == failed.getJobInstanceId());
		assertThat(paymentTransactionRepository.count()).isEqualTo(6);
		assertThat(walletRepository.findByMemberId(2L).orElseThrow().getBalance()).isEqualTo(3000);
		for (long sellerId = 101; sellerId <= 103; sellerId++) {
			assertThat(walletRepository.findByMemberId(sellerId).orElseThrow().getBalance()).isEqualTo(9000);
		}
	}

	@Test
	void heldAndCanceledSourcesProduceNoPayouts() throws Exception {
		createTestData(2, 2);
		jdbcTemplate.update("UPDATE payment_settlement SET created_at = ?", LocalDateTime.now().plusDays(1));
		List<Settlement> canceled = settlementRepository.findAll().subList(0, 2);
		canceled.forEach(Settlement::cancel);
		settlementRepository.saveAll(canceled);

		JobExecution execution = jobOperatorTestUtils.startJob();

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(payoutRepository.count()).isZero();
		assertThat(paymentTransactionRepository.count()).isZero();
		assertThat(walletRepository.findAll()).allMatch(w -> w.getBalance() == 0);
	}
}
