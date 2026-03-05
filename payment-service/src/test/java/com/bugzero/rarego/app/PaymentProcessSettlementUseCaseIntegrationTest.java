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
import com.bugzero.rarego.out.PaymentMemberRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementFeeRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.out.WalletRepository;

@SpringBootTest(properties = "custom.payment.settlement.holdDays=-1")
class PaymentProcessSettlementUseCaseIntegrationTest {
	private final Long SYSTEM_ID = 1L; // 실제 설정된 시스템 ID에 맞게 조정

	@MockitoBean
	private KafkaTemplate<String, Object> kafkaTemplate;

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
		settlementFeeRepository.deleteAll();
		paymentTransactionRepository.deleteAll();
		settlementRepository.deleteAll();
		walletRepository.deleteAll();
		memberRepository.deleteAll();

		// 1. 시스템 유저 및 지갑 생성
		createMemberAndWallet(SYSTEM_ID, "system");

		// 2. 판매자 생성
		createMemberAndWallet(100L, "seller");
	}

	@Test
	@DisplayName("동시성 테스트: 여러 스레드가 동일한 정산 건을 처리하려 해도 비관적 락에 의해 1회만 정산되어야 한다")
	void concurrency_lock_check() throws InterruptedException {
		// given
		PaymentMember seller = memberRepository.findById(100L).get();
		createSettlement(seller, 10000, 1000);

		// 처리 대상 조회 (ItemReader 역할 시뮬레이션)
		List<Settlement> targets = settlementRepository.findAll();

		int threadCount = 5;
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		// when: 5개 스레드가 동시에 같은 리스트를 정산 처리 시도
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				try {
					// UseCase 내부의 processSellerDeposits에서 지갑에 FOR UPDATE 락이 걸림
					useCase.processSettlements(targets);
				} catch (Exception e) {
					// 락 경합으로 인한 예외 발생 가능 (정상)
				} finally {
					latch.countDown();
				}
			});
		}
		latch.await();

		// then: 판매자 지갑은 단 1번(10000원)만 입금되어야 함
		Wallet sellerWallet = walletRepository.findByMemberId(100L).get();
		assertThat(sellerWallet.getBalance()).isEqualTo(10000);

		// 수수료 대기열에도 1건만 존재해야 함
		assertThat(settlementFeeRepository.count()).isEqualTo(1);

		// Phase 2: 수수료 처리 실행
		paymentSettlementProcessor.processFees();

		// 시스템 지갑 확인
		Wallet systemWallet = walletRepository.findByMemberId(SYSTEM_ID).get();
		assertThat(systemWallet.getBalance()).isEqualTo(1000);
		assertThat(settlementFeeRepository.count()).isZero();
	}

	@Test
	@DisplayName("벌크 처리 테스트: 한 판매자의 여러 정산 건이 한 번의 지갑 업데이트로 처리되어야 한다")
	void bulk_settlement_test() {
		// given
		PaymentMember seller = memberRepository.findById(100L).get();
		createSettlement(seller, 10000, 1000);
		createSettlement(seller, 20000, 2000);
		createSettlement(seller, 30000, 3000);

		List<Settlement> targets = settlementRepository.findAll();

		// when
		useCase.processSettlements(targets);

		// then
		// 1. 판매자 지갑 잔액 합산 확인 (10000+20000+30000)
		Wallet sellerWallet = walletRepository.findByMemberId(100L).get();
		assertThat(sellerWallet.getBalance()).isEqualTo(60000);

		// 2. 정산 상태 확인
		List<Settlement> results = settlementRepository.findAll();
		assertThat(results).allMatch(s -> s.getStatus() == SettlementStatus.DONE);

		// 3. 수수료 큐에 3건 적재 확인
		assertThat(settlementFeeRepository.count()).isEqualTo(3);

		// 4. 수수료 일괄 입금 실행
		paymentSettlementProcessor.processFees();

		Wallet systemWallet = walletRepository.findByMemberId(SYSTEM_ID).get();
		assertThat(systemWallet.getBalance()).isEqualTo(6000); // (1000+2000+3000)
	}

	@Test
	@DisplayName("원자성 테스트: 처리 중 예외 발생 시 해당 리스트 전체가 롤백되어야 한다")
	void atomicity_test() {
		// given
		PaymentMember seller = memberRepository.findById(100L).get();
		createSettlement(seller, 10000, 1000);

		// 강제로 지갑을 삭제하여 예외 유도
		walletRepository.deleteAll();

		List<Settlement> targets = settlementRepository.findAll();

		// when
		assertThatThrownBy(() -> useCase.processSettlements(targets))
			.isInstanceOf(RuntimeException.class);

		// then
		// 롤백되었으므로 정산 상태가 여전히 READY여야 함
		Settlement result = settlementRepository.findAll().get(0);
		assertThat(result.getStatus()).isEqualTo(SettlementStatus.READY);

		// 수수료 큐도 비어있어야 함
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

	private void createMemberAndWallet(Long id, String name) {
		PaymentMember member = createMember(id, name);
		createWallet(member);
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
