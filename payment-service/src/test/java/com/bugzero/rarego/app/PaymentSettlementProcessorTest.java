package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementFee;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementFeeRepository;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementProcessorTest {

	@InjectMocks
	private PaymentSettlementProcessor processor;

	@Mock
	private PaymentSupport paymentSupport;

	@Mock
	private PaymentTransactionRepository paymentTransactionRepository;

	@Mock
	private SettlementFeeRepository settlementFeeRepository;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(processor, "systemMemberId", 2L);
	}

	@Test
	@DisplayName("processSellerDeposits 성공: 여러 건의 정산을 합산하여 입금하고 각각의 수수료를 저장한다")
	void processSellerDeposits_success() {
		// given
		Long sellerId = 100L;

		// 2건의 정산 데이터 준비 (금액: 10000, 20000 / 수수료: 1000, 2000)
		Settlement s1 = mock(Settlement.class);
		given(s1.getId()).willReturn(1L);
		given(s1.getSettlementAmount()).willReturn(10000);
		given(s1.getFeeAmount()).willReturn(1000);

		Settlement s2 = mock(Settlement.class);
		given(s2.getId()).willReturn(2L);
		given(s2.getSettlementAmount()).willReturn(20000);
		given(s2.getFeeAmount()).willReturn(2000);

		List<Settlement> settlements = List.of(s1, s2);
		int expectedTotalSettlement = 30000;

		Wallet sellerWallet = mock(Wallet.class);
		PaymentMember seller = mock(PaymentMember.class);
		given(paymentSupport.findWalletByMemberIdForUpdate(sellerId)).willReturn(sellerWallet);
		given(sellerWallet.getMember()).willReturn(seller);

		// when
		processor.processSellerDeposits(sellerId, settlements);

		// then
		// 1. 합산 금액이 한 번에 입금되었는지 확인
		verify(sellerWallet, times(1)).addBalance(expectedTotalSettlement);

		// 2. 각 정산 건에 대해 완료 처리 및 트랜잭션 기록 확인
		verify(s1).complete();
		verify(s2).complete();
		verify(paymentTransactionRepository, times(2)).save(any(PaymentTransaction.class));

		// 3. 각 정산 건에 대해 수수료 데이터가 저장되었는지 확인
		verify(settlementFeeRepository, times(2)).save(any(SettlementFee.class));
	}

	@Test
	@DisplayName("processFees 성공: 1000건 단위로 수수료를 조회하여 시스템 지갑에 합산 입금한다")
	void processFees_success() {
		// given
		Long systemMemberId = 2L;

		SettlementFee fee1 = mock(SettlementFee.class);
		given(fee1.getFeeAmount()).willReturn(5000);

		SettlementFee fee2 = mock(SettlementFee.class);
		given(fee2.getFeeAmount()).willReturn(5000);

		List<SettlementFee> fees = List.of(fee1, fee2);
		int expectedTotalFee = 10000;

		Wallet systemWallet = mock(Wallet.class);
		PaymentMember systemMember = mock(PaymentMember.class);

		// [변경] findTop1000ByOrderByIdAsc 메서드 스터빙
		given(settlementFeeRepository.findTop1000ByOrderByIdAsc()).willReturn(fees);
		given(paymentSupport.findWalletByMemberIdForUpdate(systemMemberId)).willReturn(systemWallet);
		given(systemWallet.getMember()).willReturn(systemMember);

		// when
		// [변경] 파라미터 없이 호출
		int processedCount = processor.processFees();

		// then
		assertThat(processedCount).isEqualTo(2);

		// 1. 시스템 지갑에 합산 입금 확인
		verify(systemWallet).addBalance(expectedTotalFee);

		// 2. 수수료 데이터 일괄 삭제 확인
		verify(settlementFeeRepository).deleteAllInBatch(fees);

		// 3. 시스템 입금 트랜잭션 1건 저장 확인
		verify(paymentTransactionRepository, times(1)).save(any(PaymentTransaction.class));
	}

	@Test
	@DisplayName("processFees: 수수료 대기열이 비어있으면 0을 반환하고 종료한다")
	void processFees_empty() {
		// given
		given(settlementFeeRepository.findTop1000ByOrderByIdAsc()).willReturn(List.of());

		// when
		int processedCount = processor.processFees();

		// then
		assertThat(processedCount).isZero();
		verify(paymentSupport, never()).findWalletByMemberIdForUpdate(anyLong());
		verify(settlementFeeRepository, never()).deleteAllInBatch(anyList());
	}
}
