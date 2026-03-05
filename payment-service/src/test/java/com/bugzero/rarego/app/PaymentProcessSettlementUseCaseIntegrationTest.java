package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
	private PaymentSettlementProcessor paymentSettlementProcessor;

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
	@DisplayName("동시성 테스트: 5개의 스레드가 각기 다른 판매자의 정산을 병렬 처리해도 문제없이 수행된다")
	void concurrency_lock_check() throws InterruptedException {
		// given: 5명의 판매자와 각각의 정산 데이터 생성
		for (long i = 100; i < 105; i++) {
			PaymentMember seller = createMemberAndWallet(i, "seller" + i);
			createSettlement(seller, 10000, 1000);
		}

		int threadCount = 5;
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		// when: 각 스레드가 자신이 담당하는 판매자의 데이터만 가져와서 처리 (배치 파티셔닝 시뮬레이션)
		for (long i = 100; i < 105; i++) {
			final long sellerId = i;
			executorService.submit(() -> {
				try {
					List<Settlement> targets = settlementRepository.findAll().stream()
						.filter(s -> s.getSeller().getId().equals(sellerId))
						.toList();
					useCase.processSettlements(targets);
				} finally {
					latch.countDown();
				}
			});
		}
		latch.await();

		// then: 모든 판매자 지갑에 10000원씩 입금 완료 확인
		for (long i = 100; i < 105; i++) {
			Wallet sellerWallet = walletRepository.findByMemberId(i).get();
			assertThat(sellerWallet.getBalance()).isEqualTo(10000);
		}

		// 모든 정산 데이터 상태 변경 확인
		List<Settlement> results = settlementRepository.findAll();
		assertThat(results).allMatch(s -> s.getStatus() == SettlementStatus.DONE);

		// 수수료 일괄 처리 후 시스템 지갑 확인 (1000원 * 5건 = 5000원)
		paymentSettlementProcessor.processFees();
		Wallet systemWallet = walletRepository.findByMemberId(2L).get();
		assertThat(systemWallet.getBalance()).isEqualTo(5000);
	}

	@Test
	@DisplayName("벌크 처리 테스트: 한 판매자의 여러 정산 건이 한 번의 지갑 업데이트로 처리되어야 한다")
	void bulk_settlement_test() {
		// given
		PaymentMember seller = createMemberAndWallet(100L, "seller");
		createSettlement(seller, 10000, 1000);
		createSettlement(seller, 20000, 2000);
		createSettlement(seller, 30000, 3000);
		List<Settlement> targets = settlementRepository.findAll();

		// when
		useCase.processSettlements(targets);

		// then: 판매자 잔액 합산 확인
		Wallet sellerWallet = walletRepository.findByMemberId(100L).get();
		assertThat(sellerWallet.getBalance()).isEqualTo(60000);

		// 정산 상태 DONE 확인
		List<Settlement> results = settlementRepository.findAll();
		assertThat(results).allMatch(s -> s.getStatus() == SettlementStatus.DONE);

		// 수수료 처리 후 시스템 잔액 확인
		paymentSettlementProcessor.processFees();
		Wallet systemWallet = walletRepository.findByMemberId(2L).get();
		assertThat(systemWallet.getBalance()).isEqualTo(6000);
	}

	@Test
	@DisplayName("원자성 테스트: 처리 중 예외 발생 시 전체 롤백되어야 한다")
	void atomicity_test() {
		// given
		PaymentMember seller = createMemberAndWallet(100L, "seller");
		createSettlement(seller, 10000, 1000);

		walletRepository.deleteAllInBatch();
		List<Settlement> targets = settlementRepository.findAll();

		// when & then
		assertThatThrownBy(() -> useCase.processSettlements(targets))
			.isInstanceOf(RuntimeException.class);

		// 롤백되어 READY 상태로 유지되는지 확인
		Settlement result = settlementRepository.findAll().get(0);
		assertThat(result.getStatus()).isEqualTo(SettlementStatus.READY);
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
		Settlement settlement = Settlement.builder()
			.auctionId(System.nanoTime())
			.seller(seller)
			.salesAmount(settlementAmount + feeAmount)
			.feeAmount(feeAmount)
			.productName("레고")
			.settlementAmount(settlementAmount)
			.status(SettlementStatus.READY)
			.build();
		settlementRepository.save(settlement);
	}
}
