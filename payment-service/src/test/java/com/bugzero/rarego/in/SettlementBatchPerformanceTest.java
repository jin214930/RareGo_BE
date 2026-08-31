package com.bugzero.rarego.in;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.util.StopWatch;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.out.PaymentMemberRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementFeeRepository;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.out.WalletRepository;

/**
 * 공통 로직을 담은 추상 클래스
 */
@SpringBatchTest
@Sql(scripts = {
	"classpath:org/springframework/batch/core/schema-drop-h2.sql",
	"classpath:org/springframework/batch/core/schema-h2.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
abstract class AbstractSettlementTest {
	private final Map<Long, Integer> expectedBalances = new HashMap<>();
	private int expectedSourceCount;

	@Autowired
	protected SettlementFeeRepository settlementFeeRepository;

	@Autowired
	protected SettlementPayoutRepository payoutRepository;

	@Autowired
	protected JobOperatorTestUtils jobOperatorTestUtils;

	@Autowired
	@Qualifier("settlementJob")
	protected Job settlementJob;

	@Autowired
	protected SettlementRepository settlementRepository;

	@Autowired
	protected WalletRepository walletRepository;

	@Autowired
	protected PaymentMemberRepository paymentMemberRepository;

	@Autowired
	protected PaymentTransactionRepository paymentTransactionRepository;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		this.jobOperatorTestUtils.setJob(settlementJob);
		// 역순 데이터 클렌징
		settlementFeeRepository.deleteAllInBatch();
		payoutRepository.deleteAllInBatch();
		paymentTransactionRepository.deleteAllInBatch();
		settlementRepository.deleteAllInBatch();
		walletRepository.deleteAllInBatch();
		paymentMemberRepository.deleteAllInBatch();
	}

	protected void createTestData(int recordCount, int userCount) {
		expectedBalances.clear();
		expectedSourceCount = recordCount * 2;
		PaymentMember system = paymentMemberRepository.save(PaymentMember.builder()
			.id(2L)
			.publicId(UUID.randomUUID().toString())
			.email("system@rarego.com")
			.nickname("system")
			.build());
		walletRepository.save(Wallet.builder().member(system).balance(0).holdingAmount(0).build());
		expectedBalances.put(2L, recordCount * 1000);
		List<PaymentMember> members = new ArrayList<>();
		List<Wallet> wallets = new ArrayList<>();
		List<Settlement> settlements = new ArrayList<>();

		// 1. 판매자 및 지갑 생성
		for (long i = 1; i <= userCount; i++) {
			PaymentMember member = PaymentMember.builder()
				.id(i + 100) // 시스템 계정과 판매자 계정을 분리한다.
				.publicId(UUID.randomUUID().toString())
				.email("seller" + i + "@rarego.com")
				.nickname("seller" + i)
				.build();
			members.add(member);

			Wallet wallet = Wallet.builder()
				.member(member)
				.balance(0)
				.holdingAmount(0)
				.build();
			wallets.add(wallet);
		}
		paymentMemberRepository.saveAll(members);
		walletRepository.saveAll(wallets);

		// 2. 정산 데이터(Settlement) 생성 및 저장
		for (int i = 0; i < recordCount; i++) {
			// userCount 범위 내에서 판매자를 순환하며 할당 (충돌 테스트용)
			PaymentMember targetSeller = members.get(i % userCount);

			List<Settlement> sources = Settlement.createPaymentSources(
				(long) i + 100,             // auctionId (unique 제약조건 고려)
				"테스트 상품 " + i,           // productName
				targetSeller,               // seller
				system,
				10000                       // salesAmount (예: 만원)
			);
			settlements.addAll(sources);
			expectedBalances.merge(targetSeller.getId(), 9000, Integer::sum);
		}

		// 일괄 저장
		settlementRepository.saveAll(settlements);
	}

	protected void runAndPrint(String label) throws Exception {
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		JobExecution jobExecution = jobOperatorTestUtils.startJob();
		stopWatch.stop();

		if (jobExecution.getStatus() == BatchStatus.FAILED) {
			System.err.println("========= [REAL FAILURE REASON FROM SLAVE STEPS] =========");

			// 부모 스텝이 아닌, 실제로 일을 한 파티션(Slave) 스텝들의 에러 메시지를 가져옵니다.
			List<String> exitMessages = jdbcTemplate.queryForList(
				"SELECT EXIT_MESSAGE FROM BATCH_STEP_EXECUTION " +
					"WHERE JOB_EXECUTION_ID = ? AND STEP_NAME LIKE '%:partition%' " +
					"AND STATUS = 'FAILED'",
				String.class,
				jobExecution.getId()
			);

			for (String msg : exitMessages) {
				System.err.println(msg);
			}
			System.err.println("==========================================================");
		}

		System.out.println("[" + label + "] 실행 시간: " + stopWatch.getTotalTimeMillis() + "ms");
		assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
		assertThat(settlementRepository.findAll()).hasSize(expectedSourceCount)
			.allMatch(source -> source.getStatus() == SettlementStatus.DONE);
		assertThat(paymentTransactionRepository.count()).isEqualTo(expectedSourceCount);
		assertThat(settlementFeeRepository.count()).isZero();
		assertThat(payoutRepository.findAll()).hasSize(expectedSourceCount).allMatch(SettlementPayout::isPaid);
		assertThat(jobExecution.getStepExecutions()).anyMatch(step -> step.getStepName().equals("payoutMainStep"));
		long recipientWrites = jobExecution.getStepExecutions().stream()
			.filter(step -> step.getStepName().startsWith("settlementPayoutStep:partition"))
			.mapToLong(step -> step.getWriteCount()).sum();
		assertThat(recipientWrites).isEqualTo(expectedBalances.size());
		assertThat(jobExecution.getStepExecutions())
			.noneMatch(step -> step.getStepName().equals("systemWalletDepositStep"));
		assertThat(walletRepository.findAll()).allSatisfy(wallet ->
			assertThat(wallet.getBalance()).isEqualTo(expectedBalances.get(wallet.getMember().getId())));
	}
}

/**
 * 단일 스레드 환경 테스트
 */
@SpringBootTest(properties = {"batch.thread.size=1", "custom.payment.settlement.payoutThreadSize=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SingleThreadPerformanceTest extends AbstractSettlementTest {

	@Test
	@DisplayName("단일 스레드 - 1000건 정산 (충돌 낮음)")
	void testLowCollision() throws Exception {
		createTestData(1000, 1000);
		runAndPrint("Single-Thread | Low Collision");
	}

	@Test
	@DisplayName("단일 스레드 - 1000건 정산 (충돌 높음)")
	void testHighCollision() throws Exception {
		createTestData(1000, 10);
		runAndPrint("Single-Thread | High Collision");
	}
}

/**
 * 멀티 스레드 환경 테스트
 */
@SpringBootTest(properties = "batch.thread.size=5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiThreadPerformanceTest extends AbstractSettlementTest {

	@Test
	@DisplayName("멀티 스레드 - 1000건 정산 (충돌 낮음)")
	void testLowCollision() throws Exception {
		createTestData(1000, 1000);
		runAndPrint("Multi-Thread | Low Collision");
	}

	@Test
	@DisplayName("멀티 스레드 - 1000건 정산 (충돌 높음 - 파티셔닝 검증)")
	void testHighCollision() throws Exception {
		createTestData(1000, 10);
		runAndPrint("Multi-Thread | High Collision");
	}
}
