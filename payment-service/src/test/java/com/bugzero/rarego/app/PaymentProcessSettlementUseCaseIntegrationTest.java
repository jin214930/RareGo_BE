package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.PaymentMemberRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementFeeRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.out.WalletRepository;

@SpringBootTest(properties = "custom.payment.settlement.holdDays=-1")
class PaymentProcessSettlementUseCaseIntegrationTest {

	@MockitoBean
	private KafkaTemplate<String, Object> kafkaTemplate;

	@MockitoBean
	private OutboxUseCase outboxUseCase;

	@Autowired
	private PaymentProcessSettlementUseCase useCase;

	@Autowired
	private PaymentMemberRepository memberRepository;
	@Autowired
	private WalletRepository walletRepository;
	@Autowired
	private SettlementRepository settlementRepository;
	@Autowired
	private PaymentTransactionRepository paymentTransactionRepository;
	@Autowired
	private SettlementFeeRepository settlementFeeRepository;

	@BeforeEach
	void setUp() {
		settlementFeeRepository.deleteAllInBatch();
		paymentTransactionRepository.deleteAllInBatch();
		settlementRepository.deleteAllInBatch();
		walletRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();

		createMemberAndWallet(2L, "system");
	}

	@Test
	@DisplayName("수취인 파티셔닝으로 판매자 정산금과 시스템 수수료를 병렬 처리한다")
	void concurrency_lock_check() throws Exception {
		// given: 5명의 판매자와 각각의 정산 데이터 생성
		for (long i = 100; i < 105; i++) {
			PaymentMember seller = createMemberAndWallet(i, "seller" + i);
			createSettlement(seller, 9000, 1000);
		}

		int threadCount = 5;
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		List<Future<?>> futures = new ArrayList<>();

		// when: 실제 배치와 동일하게 수취인 ID를 기준으로 파티셔닝한다.
		try {
			for (int i = 0; i < threadCount; i++) {
				final int partitionIndex = i;
				futures.add(executorService.submit(() -> {
					List<Settlement> targets = settlementRepository.findAll().stream()
						.filter(s -> s.getRecipient().getId() % threadCount == partitionIndex)
						.toList();
					useCase.processSettlements(targets);
				}));
			}
			for (Future<?> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		} finally {
			executorService.shutdownNow();
		}

		// then: 모든 판매자 지갑에 9000원씩 입금 완료 확인
		for (long i = 100; i < 105; i++) {
			Wallet sellerWallet = walletRepository.findByMemberId(i).get();
			assertThat(sellerWallet.getBalance()).isEqualTo(9000);
		}

		// 모든 정산 데이터 상태 변경 확인
		List<Settlement> results = settlementRepository.findAll();
		assertThat(results).hasSize(10).allMatch(s -> s.getStatus() == SettlementStatus.DONE);

		// 별도 수수료 처리 없이 시스템 지갑에도 입금된다.
		Wallet systemWallet = walletRepository.findByMemberId(2L).get();
		assertThat(systemWallet.getBalance()).isEqualTo(5000);
		assertThat(paymentTransactionRepository.count()).isEqualTo(10);
		assertThat(settlementFeeRepository.count()).isZero();
	}

	@Test
	@DisplayName("벌크 처리 테스트: 한 판매자의 여러 정산 건이 한 번의 지갑 업데이트로 처리되어야 한다")
	void bulk_settlement_test() {
		// given
		PaymentMember seller = createMemberAndWallet(100L, "seller");
		createSettlement(seller, 9000, 1000);
		createSettlement(seller, 18000, 2000);
		createSettlement(seller, 27000, 3000);
		List<Settlement> targets = settlementRepository.findAll();

		// when
		useCase.processSettlements(targets);

		// then: 판매자 잔액 합산 확인
		Wallet sellerWallet = walletRepository.findByMemberId(100L).get();
		assertThat(sellerWallet.getBalance()).isEqualTo(54000);

		// 정산 상태 DONE 확인
		List<Settlement> results = settlementRepository.findAll();
		assertThat(results).allMatch(s -> s.getStatus() == SettlementStatus.DONE);

		// 동일한 처리 경로에서 시스템 수수료도 합산 입금된다.
		Wallet systemWallet = walletRepository.findByMemberId(2L).get();
		assertThat(systemWallet.getBalance()).isEqualTo(6000);
		assertThat(paymentTransactionRepository.count()).isEqualTo(6);
		assertThat(settlementFeeRepository.count()).isZero();
	}

	@Test
	@DisplayName("원자성 테스트: 처리 중 예외 발생 시 전체 롤백되어야 한다")
	void atomicity_test() {
		// given
		PaymentMember seller = createMemberAndWallet(100L, "seller");
		createSettlement(seller, 9000, 1000);

		doThrow(new IllegalStateException("outbox failure"))
			.when(outboxUseCase).saveOutbox(any());
		List<Settlement> targets = settlementRepository.findAll();

		// when & then
		assertThatThrownBy(() -> useCase.processSettlements(targets))
			.isInstanceOf(RuntimeException.class);

		// 롤백되어 READY 상태로 유지되는지 확인
		assertThat(settlementRepository.findAll()).hasSize(2)
			.allMatch(s -> s.getStatus() == SettlementStatus.READY);
		assertThat(walletRepository.findAll()).allMatch(wallet -> wallet.getBalance() == 0);
		assertThat(paymentTransactionRepository.count()).isZero();
		assertThat(settlementFeeRepository.count()).isZero();
	}

	// --- Helper Methods ---
	private PaymentMember createMember(Long id, String name) {
		PaymentMember member = PaymentMember.builder()
			.id(id)
			.publicId(UUID.randomUUID().toString())
			.email(name + "@test.com")
			.nickname(name)
			.createdAt(LocalDateTime.now())
			.updatedAt(LocalDateTime.now())
			.build();
		return memberRepository.save(member);
	}

	private void createWallet(PaymentMember member) {
		Wallet wallet = Wallet.builder()
			.member(member)
			.balance(0)
			.holdingAmount(0)
			.build();
		walletRepository.save(wallet);
	}

	private PaymentMember createMemberAndWallet(Long id, String name) {
		PaymentMember member = createMember(id, name);
		createWallet(member);
		return member;
	}

	private void createSettlement(PaymentMember seller, int settlementAmount, int feeAmount) {
		PaymentMember system = memberRepository.findById(2L).orElseThrow();
		settlementRepository.saveAll(Settlement.createPaymentSources(
			System.nanoTime(), "레고", seller, system, settlementAmount + feeAmount));
	}
}
