package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementProcessorTest {
	@InjectMocks
	private PaymentSettlementProcessor processor;
	@Mock
	private PaymentSupport paymentSupport;
	@Mock
	private PaymentTransactionRepository transactionRepository;
	@Mock
	private SettlementPayoutRepository payoutRepository;
	@Mock
	private OutboxUseCase outboxUseCase;

	private final PaymentMember seller = PaymentMember.builder().id(100L).build();
	private final PaymentMember system = PaymentMember.builder().id(2L).build();

	@Test
	void sellerProceedsUpdateWalletOnceWithPerSourceLedgerAndCompletedEvents() {
		List<SettlementPayout> payouts = List.of(
			pending(Settlement.create(1L, "상품1", seller, 100000)),
			pending(Settlement.create(2L, "상품2", seller, 200000)));
		Wallet wallet = wallet(seller, 5000, payouts);

		processor.processRecipientDeposits(10L, 100L);

		verify(wallet).addBalance(270000);
		assertThat(wallet.getBalance()).isEqualTo(275000);
		assertThat(payouts).allMatch(SettlementPayout::isPaid);
		ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
		verify(transactionRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues()).extracting(PaymentTransaction::getBalanceAfter)
			.containsExactly(95000, 275000);
		assertThat(captor.getAllValues())
			.allMatch(t -> t.getTransactionType() == WalletTransactionType.SETTLEMENT_PAID);
		ArgumentCaptor<SettlementFinishedEvent> events = ArgumentCaptor.forClass(SettlementFinishedEvent.class);
		verify(outboxUseCase).saveOutbox(events.capture());
		assertThat(events.getValue().settlements()).hasSize(2).allMatch(dto -> dto.status().equals("DONE"));
	}

	@Test
	void systemFeesKeepSourceReferencesWithoutSellerEvents() {
		SettlementPayout first = pending(Settlement.createPaymentSources(1L, "상품1", seller, system, 100000).getLast());
		SettlementPayout second = pending(Settlement.createPaymentSources(2L, "상품2", seller, system, 200000).getLast());
		Wallet wallet = wallet(system, 7000, List.of(first, second));

		processor.processRecipientDeposits(10L, 2L);

		verify(wallet).addBalance(30000);
		assertThat(wallet.getBalance()).isEqualTo(37000);
		ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
		verify(transactionRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues()).extracting(PaymentTransaction::getReferenceId).containsExactly(1L, 2L);
		assertThat(captor.getAllValues()).allMatch(t -> t.getTransactionType() == WalletTransactionType.SETTLEMENT_FEE);
		verifyNoInteractions(outboxUseCase);
	}

	@Test
	void zeroFeeCompletesWithoutWalletUpdate() {
		SettlementPayout fee = pending(Settlement.createPaymentSources(1L, "상품", seller, system, 9).getLast());
		Wallet wallet = wallet(system, 50, List.of(fee));

		processor.processRecipientDeposits(10L, 2L);

		assertThat(fee.isPaid()).isTrue();
		verify(wallet, never()).addBalance(anyInt());
		verify(transactionRepository).save(argThat(t -> t.getBalanceDelta() == 0 && t.getBalanceAfter() == 50));
		verifyNoInteractions(outboxUseCase);
	}

	@Test
	void overflowIsRejectedBeforeAnyPaymentSideEffect() {
		SettlementPayout payout = pending(Settlement.createFromForfeit(1L, "상품", seller, 100));
		Wallet wallet = wallet(seller, Integer.MAX_VALUE, List.of(payout));

		assertThatExceptionOfType(ArithmeticException.class)
			.isThrownBy(() -> processor.processRecipientDeposits(10L, 100L));

		assertThat(payout.isPaid()).isFalse();
		verify(wallet, never()).addBalance(anyInt());
		verifyNoInteractions(transactionRepository, outboxUseCase);
	}

	@Test
	void noPendingPayoutIsANoOp() {
		Wallet wallet = wallet(seller, 0, List.of());
		processor.processRecipientDeposits(10L, 100L);
		verify(wallet, never()).addBalance(anyInt());
		verifyNoInteractions(transactionRepository, outboxUseCase);
	}

	@Test
	void largeRecipientSplitsEventsButDepositsOnlyOnce() {
		List<SettlementPayout> payouts = LongStream.rangeClosed(1, 205)
			.mapToObj(id -> pending(Settlement.createFromForfeit(id, "상품", seller, 10))).toList();
		Wallet wallet = wallet(seller, 0, payouts);

		processor.processRecipientDeposits(10L, 100L);

		verify(wallet).addBalance(2050);
		ArgumentCaptor<SettlementFinishedEvent> events = ArgumentCaptor.forClass(SettlementFinishedEvent.class);
		verify(outboxUseCase, times(3)).saveOutbox(events.capture());
		assertThat(events.getAllValues()).extracting(event -> event.settlements().size()).containsExactly(100, 100, 5);
	}

	private SettlementPayout pending(Settlement settlement) {
		ReflectionTestUtils.setField(settlement, "id", settlement.getAuctionId());
		ReflectionTestUtils.setField(settlement, "status", SettlementStatus.PENDING);
		return SettlementPayout.builder().runId(10L).settlement(settlement).build();
	}

	private Wallet wallet(PaymentMember member, int balance, List<SettlementPayout> payouts) {
		Wallet wallet = spy(Wallet.builder().member(member).balance(balance).build());
		given(paymentSupport.findWalletByMemberIdForUpdate(member.getId())).willReturn(wallet);
		given(payoutRepository.findPendingForUpdate(10L, member.getId())).willReturn(payouts);
		return wallet;
	}
}
