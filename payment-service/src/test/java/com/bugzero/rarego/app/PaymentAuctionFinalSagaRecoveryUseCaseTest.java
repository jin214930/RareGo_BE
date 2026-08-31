package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.bugzero.rarego.domain.AuctionFinalPaymentSagaStep;
import com.bugzero.rarego.domain.Deposit;
import com.bugzero.rarego.domain.DepositStatus;
import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.PaymentSagaExecution;
import com.bugzero.rarego.domain.PaymentSagaType;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.AuctionOrderApiClient;
import com.bugzero.rarego.out.DepositRepository;
import com.bugzero.rarego.out.PaymentSagaExecutionRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.shared.auction.dto.AuctionOrderDto;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentCompletedEvent;

@ExtendWith(MockitoExtension.class)
class PaymentAuctionFinalSagaRecoveryUseCaseTest {
	@InjectMocks
	private PaymentAuctionFinalSagaRecoveryUseCase useCase;
	@Mock
	private PaymentSagaExecutionRepository sagaRepository;
	@Mock
	private AuctionOrderApiClient auctionOrderApiClient;
	@Mock
	private DepositRepository depositRepository;
	@Mock
	private PaymentTransactionRepository transactionRepository;
	@Mock
	private PaymentCreateSettlementUseCase sourceUseCase;
	@Mock
	private PaymentSupport support;
	@Mock
	private OutboxUseCase outbox;
	@Mock
	private PaymentSagaTracker tracker;
	@Mock
	private PlatformTransactionManager transactionManager;

	private final PaymentMember seller = PaymentMember.builder().id(10L).build();
	private final PaymentMember buyer = PaymentMember.builder().id(20L).build();
	private PaymentSagaExecution saga;

	@BeforeEach
	void setUp() {
		saga = PaymentSagaExecution.start(PaymentSagaType.AUCTION_FINAL_PAYMENT, "100", "INITIATED");
		saga.markCheckpoint(AuctionFinalPaymentSagaStep.SETTLEMENT_READY.name());
		saga.markFailed("SETTLEMENT_READY", new IllegalStateException("실패"), LocalDateTime.now().minusMinutes(1), true);
		given(sagaRepository.findBySagaTypeAndBusinessKeyForUpdate(PaymentSagaType.AUCTION_FINAL_PAYMENT, "100"))
			.willReturn(Optional.of(saga));
		given(tracker.startOrResume(eq(saga), any())).willReturn("command-100");
		given(auctionOrderApiClient.getOrder(100L)).willReturn(new AuctionOrderDto(
			1L, 100L, 10L, 20L, 100000, "SUCCESS", LocalDateTime.now(), "레고"));
		given(support.findMemberById(10L)).willReturn(seller);
	}

	@Test
	void completePairSkipsDebitAndSourceCreation() {
		given(sourceUseCase.hasCompletePaymentSources(100L, "레고", seller, 100000)).willReturn(true);

		useCase.recoverSingle("100");

		verify(sourceUseCase, never()).createForPayment(anyLong(), anyString(), any(), anyInt());
		verifyNoInteractions(depositRepository, transactionRepository, outbox);
		verify(tracker).markCompleted(
			PaymentSagaType.AUCTION_FINAL_PAYMENT, "100", AuctionFinalPaymentSagaStep.COMPLETED);
	}

	@Test
	void partialPairIsRepairedDespiteReadyCheckpointWithoutDebitingAgain() {
		Wallet wallet = arrangeAlreadyDebited();

		useCase.recoverSingle("100");

		verify(sourceUseCase).createForPayment(100L, "레고", seller, 100000);
		assertThat(wallet.getBalance()).isEqualTo(100000);
		verifyNoInteractions(transactionRepository);
		verify(auctionOrderApiClient, never()).completeOrder(anyLong(), anyString());
		verify(outbox).saveOutbox(any(AuctionPaymentCompletedEvent.class));
		verify(tracker).markCompleted(saga, AuctionFinalPaymentSagaStep.COMPLETED);
	}

	@Test
	void completedCheckpointDoesNotPreventRepairOrFinalStatusUpdate() {
		saga.markCheckpoint(AuctionFinalPaymentSagaStep.COMPLETED.name());
		arrangeAlreadyDebited();

		useCase.recoverSingle("100");

		verify(sourceUseCase).createForPayment(100L, "레고", seller, 100000);
		verifyNoInteractions(transactionRepository, outbox);
		verify(tracker).markCompleted(saga, AuctionFinalPaymentSagaStep.COMPLETED);
		verify(tracker, never()).markCheckpoint(saga, AuctionFinalPaymentSagaStep.SETTLEMENT_READY);
	}

	@Test
	void conflictingSourceStopsRecoveryBeforeDebitOrCompletion() {
		given(sourceUseCase.hasCompletePaymentSources(100L, "레고", seller, 100000))
			.willThrow(new IllegalStateException("원천 금액 불일치"));

		assertThatIllegalStateException().isThrownBy(() -> useCase.recoverSingle("100"));

		verifyNoInteractions(depositRepository, transactionRepository, outbox);
		verify(tracker).markFailed(eq(saga), eq(AuctionFinalPaymentSagaStep.SETTLEMENT_READY), any());
		verify(tracker, never()).markCompleted(any(PaymentSagaExecution.class), any());
	}

	private Wallet arrangeAlreadyDebited() {
		Deposit deposit = Deposit.builder()
			.member(buyer).auctionId(100L).amount(10000).status(DepositStatus.USED).build();
		Wallet wallet = Wallet.builder().member(buyer).balance(100000).holdingAmount(0).build();
		given(depositRepository.findByMemberIdAndAuctionId(20L, 100L)).willReturn(Optional.of(deposit));
		given(support.findWalletByMemberIdForUpdate(20L)).willReturn(wallet);
		given(support.findMemberById(20L)).willReturn(buyer);
		return wallet;
	}
}
