package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

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
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementRepository;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementProcessorTest {
	@InjectMocks
	private PaymentSettlementProcessor processor;
	@Mock
	private PaymentSupport paymentSupport;
	@Mock
	private PaymentTransactionRepository transactionRepository;
	@Mock
	private SettlementRepository settlementRepository;

	private final PaymentMember seller = PaymentMember.builder().id(100L).build();
	private final PaymentMember system = PaymentMember.builder().id(2L).build();

	@Test
	void sellerProceedsAreSummedAndRecordedPerSource() {
		List<Settlement> sources = List.of(
			Settlement.createPaymentSources(1L, "상품1", seller, system, 100000).getFirst(),
			Settlement.createPaymentSources(2L, "상품2", seller, system, 200000).getFirst());
		Wallet wallet = Wallet.builder().member(seller).balance(5000).build();
		given(paymentSupport.findWalletByMemberIdForUpdate(100L)).willReturn(wallet);

		processor.processRecipientDeposits(100L, sources);

		assertThat(wallet.getBalance()).isEqualTo(275000);
		assertThat(sources).allMatch(source -> source.getStatus() == SettlementStatus.DONE);
		ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
		verify(transactionRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues()).extracting(PaymentTransaction::getBalanceDelta)
			.containsExactly(90000, 180000);
		assertThat(captor.getAllValues()).extracting(PaymentTransaction::getBalanceAfter)
			.containsExactly(95000, 275000);
		assertThat(captor.getAllValues())
			.allMatch(t -> t.getTransactionType() == WalletTransactionType.SETTLEMENT_PAID);
		verify(settlementRepository).saveAll(sources);
	}

	@Test
	void feeSourcesUseSystemRecipientAndPreserveEachSourceReference() {
		PaymentMember otherSeller = PaymentMember.builder().id(200L).build();
		Settlement first = Settlement.createPaymentSources(1L, "상품1", seller, system, 100000).getLast();
		Settlement second = Settlement.createPaymentSources(2L, "상품2", otherSeller, system, 200000).getLast();
		ReflectionTestUtils.setField(first, "id", 11L);
		ReflectionTestUtils.setField(second, "id", 12L);
		Wallet wallet = Wallet.builder().member(system).balance(7000).build();
		given(paymentSupport.findWalletByMemberIdForUpdate(2L)).willReturn(wallet);

		processor.processRecipientDeposits(2L, List.of(first, second));

		assertThat(wallet.getBalance()).isEqualTo(37000);
		ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
		verify(transactionRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues()).extracting(PaymentTransaction::getReferenceId).containsExactly(11L, 12L);
		assertThat(captor.getAllValues()).extracting(PaymentTransaction::getBalanceAfter).containsExactly(17000, 37000);
		assertThat(captor.getAllValues()).allMatch(t -> t.getTransactionType() == WalletTransactionType.SETTLEMENT_FEE);
		assertThat(captor.getAllValues()).allMatch(t -> t.getWallet() == wallet && t.getMember() == system);
		verify(paymentSupport, never()).findWalletByMemberIdForUpdate(100L);
		verify(paymentSupport, never()).findWalletByMemberIdForUpdate(200L);
	}

	@Test
	void forfeitStillUsesSellerPaymentType() {
		Settlement source = Settlement.createFromForfeit(1L, "상품", seller, 10000);
		Wallet wallet = Wallet.builder().member(seller).balance(0).build();
		given(paymentSupport.findWalletByMemberIdForUpdate(100L)).willReturn(wallet);

		processor.processRecipientDeposits(100L, List.of(source));

		assertThat(wallet.getBalance()).isEqualTo(10000);
		verify(transactionRepository)
			.save(argThat(t -> t.getTransactionType() == WalletTransactionType.SETTLEMENT_PAID));
	}

	@Test
	void zeroFeeCompletesWithoutIncreasingBalance() {
		Settlement fee = Settlement.createPaymentSources(1L, "상품", seller, system, 9).getLast();
		Wallet wallet = Wallet.builder().member(system).balance(50).build();
		given(paymentSupport.findWalletByMemberIdForUpdate(2L)).willReturn(wallet);

		processor.processRecipientDeposits(2L, List.of(fee));

		assertThat(wallet.getBalance()).isEqualTo(50);
		assertThat(fee.getStatus()).isEqualTo(SettlementStatus.DONE);
		verify(transactionRepository).save(argThat(t -> t.getBalanceDelta() == 0 && t.getBalanceAfter() == 50));
	}

	@Test
	void emptySourcesDoNothing() {
		processor.processRecipientDeposits(100L, List.of());
		processor.processRecipientDeposits(100L, null);
		verifyNoInteractions(paymentSupport, transactionRepository, settlementRepository);
	}
}
