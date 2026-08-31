package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.PaymentMemberRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementFeeRepository;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.out.WalletRepository;

@SpringBootTest(properties = "custom.payment.settlement.holdDays=-1")
class PaymentProcessSettlementUseCaseIntegrationTest {
	@MockitoBean
	private KafkaTemplate<String, Object> kafkaTemplate;
	@MockitoBean
	private OutboxUseCase outboxUseCase;
	@MockitoSpyBean
	private SettlementPayoutRepository payoutRepository;
	@Autowired
	private PaymentProcessSettlementUseCase useCase;
	@Autowired
	private PaymentSettlementProcessor processor;
	@Autowired
	private PaymentMemberRepository memberRepository;
	@Autowired
	private WalletRepository walletRepository;
	@Autowired
	private SettlementRepository settlementRepository;
	@Autowired
	private PaymentTransactionRepository transactionRepository;
	@Autowired
	private SettlementFeeRepository feeRepository;

	@BeforeEach
	void setUp() {
		feeRepository.deleteAllInBatch();
		payoutRepository.deleteAllInBatch();
		transactionRepository.deleteAllInBatch();
		settlementRepository.deleteAllInBatch();
		walletRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		createMember(2L);
		createMember(100L);
	}

	@Test
	void preparationDoesNotDepositOrNotifyAndRecipientDepositIsIdempotent() {
		createSources(100L, 10000);
		createSources(100L, 20000);
		List<Long> ids = sourceIds();

		useCase.prepareSettlements(10L, ids);
		useCase.prepareSettlements(10L, ids);
		useCase.prepareSettlements(11L, ids);

		assertThat(payoutRepository.findAll()).hasSize(4).allMatch(p -> p.getRunId() == 10L && !p.isPaid());
		assertThat(settlementRepository.findAll()).allMatch(s -> s.getStatus() == SettlementStatus.PENDING);
		assertThat(walletRepository.findAll()).allMatch(w -> w.getBalance() == 0);
		assertThat(transactionRepository.count()).isZero();
		verify(outboxUseCase, never()).saveOutbox(any());

		processor.processRecipientDeposits(11L, 100L);
		assertThat(balance(100L)).isZero();
		processor.processRecipientDeposits(10L, 100L);
		processor.processRecipientDeposits(10L, 2L);
		processor.processRecipientDeposits(10L, 100L);
		processor.processRecipientDeposits(10L, 2L);
		useCase.prepareSettlements(12L, ids);

		assertThat(balance(100L)).isEqualTo(27000);
		assertThat(balance(2L)).isEqualTo(3000);
		assertThat(transactionRepository.count()).isEqualTo(4);
		assertThat(payoutRepository.findAll()).hasSize(4).allMatch(SettlementPayout::isPaid);
		assertThat(settlementRepository.findAll()).allMatch(s -> s.getStatus() == SettlementStatus.DONE);
		assertThat(feeRepository.count()).isZero();
		verify(outboxUseCase).saveOutbox(any());
	}

	@Test
	void databaseRejectsDuplicatePayoutForTheSameSource() {
		createSources(100L, 10000);
		useCase.prepareSettlements(10L, sourceIds());
		Settlement source = settlementRepository.findAll().getFirst();

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> payoutRepository.saveAndFlush(
				SettlementPayout.builder().runId(11L).settlement(source).build()));

		assertThat(payoutRepository.count()).isEqualTo(2);
		assertThat(walletRepository.findAll()).allMatch(w -> w.getBalance() == 0);
	}

	@Test
	void canceledSourcesAreNotPrepared() {
		List<Settlement> sources = createSources(100L, 10000);
		sources.forEach(Settlement::cancel);
		settlementRepository.saveAll(sources);

		useCase.prepareSettlements(10L, sourceIds());

		assertThat(payoutRepository.count()).isZero();
		assertThat(settlementRepository.findAll()).allMatch(s -> s.getStatus() == SettlementStatus.CANCELED);
	}

	@Test
	void failedQueueInsertRollsBackSourceTransition() {
		createSources(100L, 10000);
		doThrow(new IllegalStateException("queue failure")).when(payoutRepository).save(any(SettlementPayout.class));

		assertThatIllegalStateException().isThrownBy(() -> useCase.prepareSettlements(10L, sourceIds()));

		assertThat(settlementRepository.findAll()).allMatch(s -> s.getStatus() == SettlementStatus.READY);
		assertThat(payoutRepository.count()).isZero();
		assertThat(transactionRepository.count()).isZero();
	}

	@Test
	void outboxFailureRollsBackRecipientAndRetryPaysOnce() {
		createSources(100L, 10000);
		useCase.prepareSettlements(10L, sourceIds());
		doThrow(new IllegalStateException("outbox failure")).when(outboxUseCase).saveOutbox(any());

		assertThatIllegalStateException().isThrownBy(() -> processor.processRecipientDeposits(10L, 100L));

		assertThat(balance(100L)).isZero();
		assertThat(transactionRepository.count()).isZero();
		assertThat(payoutRepository.findAll()).noneMatch(SettlementPayout::isPaid);
		assertThat(settlementRepository.findAll()).allMatch(s -> s.getStatus() == SettlementStatus.PENDING);

		reset(outboxUseCase);
		processor.processRecipientDeposits(10L, 100L);
		assertThat(balance(100L)).isEqualTo(9000);
		assertThat(transactionRepository.count()).isEqualTo(1);
	}

	@Test
	void overlappingRunsCannotPrepareOrPayTheSameSourceTwice() throws Exception {
		createSources(100L, 10000);
		List<Long> ids = sourceIds();
		runConcurrently(List.of(
			() -> {
				useCase.prepareSettlements(10L, ids);
				return null;
			},
			() -> {
				useCase.prepareSettlements(11L, ids);
				return null;
			}));
		assertThat(payoutRepository.count()).isEqualTo(2);
		List<Callable<Void>> deposits = new ArrayList<>();
		for (int repeat = 0; repeat < 2; repeat++) {
			for (long run : List.of(10L, 11L)) {
				deposits.add(() -> {
					processor.processRecipientDeposits(run, 100L);
					return null;
				});
				deposits.add(() -> {
					processor.processRecipientDeposits(run, 2L);
					return null;
				});
			}
		}
		runConcurrently(deposits);

		assertThat(balance(100L)).isEqualTo(9000);
		assertThat(balance(2L)).isEqualTo(1000);
		assertThat(transactionRepository.count()).isEqualTo(2);
		assertThat(payoutRepository.findAll()).allMatch(SettlementPayout::isPaid);
	}

	private void runConcurrently(List<Callable<Void>> tasks) throws Exception {
		try (var executor = Executors.newFixedThreadPool(tasks.size())) {
			List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
			for (Future<Void> future : futures) {
				future.get(5, TimeUnit.SECONDS);
			}
		}
	}

	private List<Long> sourceIds() {
		return settlementRepository.findAll().stream().map(Settlement::getId).toList();
	}

	private int balance(Long memberId) {
		return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
	}

	private void createMember(Long id) {
		PaymentMember member = memberRepository.save(PaymentMember.builder().id(id)
			.publicId(UUID.randomUUID().toString()).email(id + "@test.com").nickname("member" + id)
			.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		walletRepository.save(Wallet.builder().member(member).balance(0).holdingAmount(0).build());
	}

	private List<Settlement> createSources(Long sellerId, int salesAmount) {
		return settlementRepository.saveAll(Settlement.createPaymentSources(System.nanoTime(), "상품",
			memberRepository.findById(sellerId).orElseThrow(), memberRepository.findById(2L).orElseThrow(),
			salesAmount));
	}
}
