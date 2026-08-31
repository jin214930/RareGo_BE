package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.SettlementBulkRepository;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementProcessorTest {
	@InjectMocks
	private PaymentSettlementProcessor processor;
	@Mock
	private PaymentSupport paymentSupport;
	@Mock
	private SettlementBulkRepository bulkRepository;
	@Mock
	private SettlementRepository settlementRepository;
	@Mock
	private SettlementPayoutRepository payoutRepository;
	@Mock
	private OutboxUseCase outboxUseCase;

	@Test
	void combinesIndependentPartialSumsAndDepositsOnlyOnce() {
		List<SettlementPayout> payouts = List.of(partial(1L, 9000, 2), partial(2L, 18000, 3));
		Wallet wallet = wallet(5000, payouts);
		given(bulkRepository.insertTransactions(List.of(1L, 2L), 7L, 100L, 5000)).willReturn(5);
		given(settlementRepository.completeSources(List.of(1L, 2L), 100L)).willReturn(5);

		processor.processRecipientDeposits(10L, 100L);

		verify(wallet).addBalance(27000);
		assertThat(wallet.getBalance()).isEqualTo(32000);
		assertThat(payouts).allMatch(SettlementPayout::isPaid);
	}

	@Test
	void missingLedgerSourceRejectsPayment() {
		SettlementPayout payout = partial(1L, 9000, 2);
		Wallet wallet = wallet(0, List.of(payout));
		given(bulkRepository.insertTransactions(List.of(1L), 7L, 100L, 0)).willReturn(1);

		assertThatIllegalStateException().isThrownBy(() -> processor.processRecipientDeposits(10L, 100L));

		verify(wallet, never()).addBalance(anyInt());
		verifyNoInteractions(settlementRepository, outboxUseCase);
		assertThat(payout.isPaid()).isFalse();
	}

	@Test
	void mismatchedCompletionCountRejectsPayment() {
		SettlementPayout payout = partial(1L, 9000, 2);
		Wallet wallet = wallet(0, List.of(payout));
		given(bulkRepository.insertTransactions(List.of(1L), 7L, 100L, 0)).willReturn(2);
		given(settlementRepository.completeSources(List.of(1L), 100L)).willReturn(1);

		assertThatIllegalStateException().isThrownBy(() -> processor.processRecipientDeposits(10L, 100L));

		verify(wallet, never()).addBalance(anyInt());
		verifyNoInteractions(outboxUseCase);
		assertThat(payout.isPaid()).isFalse();
	}

	@Test
	void overflowIsRejectedBeforeAnyPaymentSideEffect() {
		SettlementPayout payout = partial(1L, 100, 1);
		Wallet wallet = wallet(Integer.MAX_VALUE, List.of(payout));

		assertThatExceptionOfType(ArithmeticException.class)
			.isThrownBy(() -> processor.processRecipientDeposits(10L, 100L));

		assertThat(payout.isPaid()).isFalse();
		verify(wallet, never()).addBalance(anyInt());
		verifyNoInteractions(bulkRepository, settlementRepository, outboxUseCase);
	}

	@Test
	void partialSumLargerThanIntIsRejectedBeforeLedgerInsert() {
		wallet(0, List.of(partial(1L, (long)Integer.MAX_VALUE + 1, 2)));

		assertThatExceptionOfType(ArithmeticException.class)
			.isThrownBy(() -> processor.processRecipientDeposits(10L, 100L));

		verifyNoInteractions(bulkRepository, settlementRepository, outboxUseCase);
	}

	@Test
	void noPendingPayoutIsANoOp() {
		Wallet wallet = wallet(0, List.of());
		processor.processRecipientDeposits(10L, 100L);
		verify(wallet, never()).addBalance(anyInt());
		verifyNoInteractions(bulkRepository, settlementRepository, outboxUseCase);
	}

	private SettlementPayout partial(Long id, long amount, int count) {
		SettlementPayout payout = SettlementPayout.builder().runId(10L).chunkId(id).recipientId(100L)
			.amount(amount).sourceCount(count).build();
		ReflectionTestUtils.setField(payout, "id", id);
		return payout;
	}

	private Wallet wallet(int balance, List<SettlementPayout> payouts) {
		Wallet wallet = spy(Wallet.builder().member(PaymentMember.builder().id(100L).build()).balance(balance).build());
		ReflectionTestUtils.setField(wallet, "id", 7L);
		given(paymentSupport.findWalletByMemberIdForUpdate(100L)).willReturn(wallet);
		given(payoutRepository.findPendingForUpdate(10L, 100L)).willReturn(payouts);
		return wallet;
	}
}
