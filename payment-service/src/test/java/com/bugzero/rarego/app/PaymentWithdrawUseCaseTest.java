package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.ReferenceType;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.in.dto.WithdrawRequestDto;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.shared.auction.out.AuctionApiClient;

@ExtendWith(MockitoExtension.class)
class PaymentWithdrawUseCaseTest {

	@InjectMocks
	private PaymentWithdrawUseCase paymentWithdrawUseCase;

	@Mock
	private PaymentSupport paymentSupport;

	@Mock
	private PaymentTransactionRepository paymentTransactionRepository;

	@Mock
	private AuctionApiClient auctionApiClient;

	// ==================== 진행 중인 주문 확인 테스트 ====================

	@Test
	@DisplayName("성공: 진행 중인 주문(경매)이 있는지 외부 클라이언트를 통해 확인한다")
	void hasProcessingOrders_success() {
		// given
		String publicId = "member-uuid-123";
		given(auctionApiClient.hasProcessingOrders(publicId)).willReturn(true);

		// when
		boolean result = paymentWithdrawUseCase.hasProcessingOrders(publicId);

		// then
		assertThat(result).isTrue();
		verify(auctionApiClient, times(1)).hasProcessingOrders(publicId);
	}

	// ==================== 출금 로직 테스트 ====================

	@Test
	@DisplayName("성공: 예치금 출금이 정상 처리되고 트랜잭션 내역이 저장된다")
	void withdraw_success() {
		// given
		String publicId = "member-uuid-123";
		Long memberId = 1L;
		int initialBalance = 50000;
		int withdrawAmount = 10000;

		WithdrawRequestDto request = new WithdrawRequestDto(withdrawAmount);
		PaymentMember member = createMockMember(memberId, publicId);

		// Wallet 엔티티는 내부 비즈니스 로직(withdraw) 테스트를 위해 실제 객체로 생성
		Wallet wallet = Wallet.builder()
			.balance(initialBalance)
			.holdingAmount(0)
			.build();

		given(paymentSupport.findMemberByPublicId(publicId)).willReturn(member);
		given(paymentSupport.findWalletByMemberIdForUpdate(memberId)).willReturn(wallet);

		// when
		paymentWithdrawUseCase.withdraw(publicId, request);

		// then
		// 1. 지갑 잔액이 정확히 차감되었는지 검증
		assertThat(wallet.getBalance()).isEqualTo(initialBalance - withdrawAmount);

		// 2. 트랜잭션 저장 객체 검증
		ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
		verify(paymentTransactionRepository, times(1)).save(captor.capture());

		PaymentTransaction savedTransaction = captor.getValue();
		assertThat(savedTransaction.getMember()).isEqualTo(member);
		assertThat(savedTransaction.getWallet()).isEqualTo(wallet);
		assertThat(savedTransaction.getTransactionType()).isEqualTo(WalletTransactionType.WITHDRAW_DONE);
		assertThat(savedTransaction.getBalanceDelta()).isEqualTo(-withdrawAmount); // 차감이므로 음수
		assertThat(savedTransaction.getHoldingDelta()).isEqualTo(0);
		assertThat(savedTransaction.getBalanceAfter()).isEqualTo(initialBalance - withdrawAmount);
		assertThat(savedTransaction.getReferenceType()).isEqualTo(ReferenceType.WITHDRAW);
		assertThat(savedTransaction.getReferenceId()).isEqualTo(0L);
	}

	@Test
	@DisplayName("실패: 출금 가능 잔액이 부족하면 도메인 로직에서 예외가 발생하고 트랜잭션은 저장되지 않는다")
	void withdraw_fail_insufficient_balance() {
		// given
		String publicId = "member-uuid-123";
		Long memberId = 1L;
		int withdrawAmount = 50000;

		WithdrawRequestDto request = new WithdrawRequestDto(withdrawAmount);
		PaymentMember member = createMockMember(memberId, publicId);

		// 잔액(10,000)보다 출금 요청 금액(50,000)이 큰 상황
		Wallet wallet = Wallet.builder()
			.balance(10000)
			.holdingAmount(0)
			.build();

		given(paymentSupport.findMemberByPublicId(publicId)).willReturn(member);
		given(paymentSupport.findWalletByMemberIdForUpdate(memberId)).willReturn(wallet);

		// when & then
		assertThatThrownBy(() -> paymentWithdrawUseCase.withdraw(publicId, request))
			.isInstanceOf(CustomException.class)
			.hasFieldOrPropertyWithValue("errorType", ErrorType.INSUFFICIENT_BALANCE);

		// 출금에 실패했으므로 저장 로직은 절대 호출되지 않아야 함
		verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
	}

	@Test
	@DisplayName("실패: 회원을 찾을 수 없으면 예외가 발생한다")
	void withdraw_fail_member_not_found() {
		// given
		String publicId = "ghost-uuid";
		WithdrawRequestDto request = new WithdrawRequestDto(10000);

		given(paymentSupport.findMemberByPublicId(publicId))
			.willThrow(new CustomException(ErrorType.MEMBER_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> paymentWithdrawUseCase.withdraw(publicId, request))
			.isInstanceOf(CustomException.class)
			.hasFieldOrPropertyWithValue("errorType", ErrorType.MEMBER_NOT_FOUND);

		verify(paymentSupport, never()).findWalletByMemberIdForUpdate(anyLong());
		verify(paymentTransactionRepository, never()).save(any());
	}

	// 헬퍼 메서드
	private PaymentMember createMockMember(Long memberId, String publicId) {
		return PaymentMember.builder()
			.id(memberId)
			.publicId(publicId)
			.nickname("테스트유저")
			.build();
	}
}
