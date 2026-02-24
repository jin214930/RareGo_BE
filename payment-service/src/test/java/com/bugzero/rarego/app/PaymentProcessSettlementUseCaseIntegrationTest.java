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
import com.bugzero.rarego.domain.SettlementFee;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.out.PaymentMemberRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementFeeRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.out.WalletRepository;

@SpringBootTest(properties = "custom.payment.settlement.holdDays=-1")
class PaymentProcessSettlementUseCaseIntegrationTest {
	private final Long SYSTEM_ID = 2L;

	@MockitoBean
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Autowired
	private PaymentProcessSettlementUseCase useCase;

	// 수수료 일괄 처리를 수동으로 트리거하기 위해 주입
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

		// 2. 판매자 생성 (기본 테스트용)
		createMemberAndWallet(100L, "seller");
	}

	@Test
	@DisplayName("동시성 테스트: 동시에 5개 스레드가 정산을 시도해도 중복 정산이 없고, 수수료 지연 집계가 정상 동작한다")
	void concurrency_double_spending_check() throws InterruptedException {
		// given
		PaymentMember seller = memberRepository.findById(100L).get();
		createSettlement(seller, 10000, 1000);

		int threadCount = 5;
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);

		// when: [Phase 1] 배치 mainStep 실행 (멀티 스레드 경합)
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				try {
					useCase.processSettlements(10);
				} finally {
					latch.countDown();
				}
			});
		}
		latch.await();

		// then: [Phase 1 검증] 개별 정산 및 큐 적재 확인
		Settlement settlement = settlementRepository.findAll().get(0);
		assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.DONE);

		Wallet sellerWallet = walletRepository.findByMemberId(100L).get();
		assertThat(sellerWallet.getBalance()).isEqualTo(10000);

		// 시스템 잔액은 아직 0이어야 함 (Step 2가 안 돌았으므로)
		Wallet systemWalletBeforePhase2 = walletRepository.findByMemberId(SYSTEM_ID).get();
		assertThat(systemWalletBeforePhase2.getBalance()).isEqualTo(0);

		// 큐에 1건의 수수료 데이터가 대기 중이어야 함
		List<SettlementFee> fees = settlementFeeRepository.findAll();
		assertThat(fees).hasSize(1);
		assertThat(fees.get(0).getFeeAmount()).isEqualTo(1000);

		// when: [Phase 2] 배치 systemWalletDepositStep 실행 시뮬레이션
		paymentSettlementProcessor.processFees(1000);

		// then: [Phase 2 검증] 수수료 일괄 입금 및 큐 정리 확인
		Wallet systemWalletAfterPhase2 = walletRepository.findByMemberId(SYSTEM_ID).get();
		assertThat(systemWalletAfterPhase2.getBalance()).isEqualTo(1000); // 1000원 입금 확인

		assertThat(paymentTransactionRepository.count()).isEqualTo(2); // 판매자 입금 1건 + 시스템 수수료 1건
		assertThat(settlementFeeRepository.findAll()).isEmpty(); // 큐가 비워졌는지 확인
	}

	@Test
	@DisplayName("부분 성공 테스트: 실패 건은 재시도 대기 상태가 되고, 성공 건만 수수료 큐에 적재되어 다음 Step에서 처리된다")
	void partial_success_integration_test() {
		// given
		PaymentMember normalSeller = createMember(200L, "normal");
		createWallet(normalSeller);
		createSettlement(normalSeller, 10000, 1000);

		PaymentMember errorSeller = createMember(300L, "error");
		createSettlement(errorSeller, 20000, 2000); // 지갑이 없으므로 실패 유도

		// when: [Phase 1] 정산 UseCase 1회 실행
		int successCount = useCase.processSettlements(10);

		// then: [Phase 1 검증]
		assertThat(successCount).isEqualTo(1);

		// 1. 정상 판매자 검증
		Wallet normalWallet = walletRepository.findByMemberId(200L).get();
		assertThat(normalWallet.getBalance()).isEqualTo(10000);

		// 2. 오류 판매자 상태 검증 (실패했으므로 READY 유지 및 tryCount 증가)
		Settlement errorSettlement = findSettlementBySellerId(300L);
		assertThat(errorSettlement.getStatus()).isEqualTo(SettlementStatus.READY);
		assertThat(errorSettlement.getTryCount()).isEqualTo(1);

		// 3. 수수료 큐 적재 검증 (성공한 1000원 1건만 존재해야 함)
		List<SettlementFee> fees = settlementFeeRepository.findAll();
		assertThat(fees).hasSize(1);
		assertThat(fees.get(0).getFeeAmount()).isEqualTo(1000);

		// when: [Phase 2] 수수료 처리 Step 시뮬레이션
		paymentSettlementProcessor.processFees(1000);

		// then: [Phase 2 검증]
		Wallet systemWallet = walletRepository.findByMemberId(SYSTEM_ID).get();
		assertThat(systemWallet.getBalance()).isEqualTo(1000); // 1건분 입금 확인
		assertThat(settlementFeeRepository.findAll()).isEmpty(); // 처리 완료 후 비워짐
	}

	// --- Helper Methods ---
	// (기존 코드와 동일하므로 생략 없이 그대로 유지)
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

	private Settlement findSettlementBySellerId(Long sellerId) {
		return settlementRepository.findAll().stream()
			.filter(s -> s.getSeller().getId().equals(sellerId))
			.findFirst()
			.orElseThrow(() -> new RuntimeException("Settlement not found for seller " + sellerId));
	}
}
