package com.bugzero.rarego.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bugzero.rarego.domain.AuctionFinalPaymentSagaStep;
import com.bugzero.rarego.domain.Deposit;
import com.bugzero.rarego.domain.DepositStatus;
import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.PaymentSagaExecution;
import com.bugzero.rarego.domain.PaymentSagaExecutionStatus;
import com.bugzero.rarego.domain.PaymentSagaType;
import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.ReferenceType;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.AuctionOrderApiClient;
import com.bugzero.rarego.out.DepositRepository;
import com.bugzero.rarego.out.PaymentSagaExecutionRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.shared.auction.dto.AuctionOrderDto;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAuctionFinalSagaRecoveryUseCase {
	private static final int DEFAULT_BATCH_LIMIT = 20;
	private static final String ORDER_STATUS_PROCESSING = "PROCESSING";
	private static final String ORDER_STATUS_SUCCESS = "SUCCESS";

	private final PaymentSagaExecutionRepository sagaRepository;
	private final AuctionOrderApiClient auctionOrderApiClient;
	private final DepositRepository depositRepository;
	private final PaymentTransactionRepository transactionRepository;
	private final PaymentCreateSettlementUseCase paymentCreateSettlementUseCase;
	private final PaymentSupport paymentSupport;
	private final OutboxUseCase outboxUseCase;
	private final PaymentSagaTracker sagaTracker;
	private final PlatformTransactionManager transactionManager;

	@Value("${payment.saga.auction-final-recovery.batch-size:" + DEFAULT_BATCH_LIMIT + "}")
	private int batchSize;

	@Value("${payment.saga.auction-final-recovery.in-progress-timeout-seconds:600}")
	private long inProgressTimeoutSeconds;

	public void recoverFailedFinalPayments() {
		List<PaymentSagaExecution> failedTargets = sagaRepository.findRetryTargetsForBatch(
			PaymentSagaType.AUCTION_FINAL_PAYMENT,
			PaymentSagaExecutionStatus.FAILED,
			LocalDateTime.now(),
			batchSize
		);
		List<PaymentSagaExecution> staleInProgressTargets = sagaRepository.findStaleInProgressTargetsForBatch(
			PaymentSagaType.AUCTION_FINAL_PAYMENT,
			PaymentSagaExecutionStatus.IN_PROGRESS,
			LocalDateTime.now().minusSeconds(inProgressTimeoutSeconds),
			batchSize
		);
		List<PaymentSagaExecution> targets = mergeTargets(failedTargets, staleInProgressTargets, batchSize);

		if (targets.isEmpty()) {
			return;
		}

		int success = 0;
		int failed = 0;
		for (PaymentSagaExecution target : targets) {
			try {
				runInNewTransaction(() -> recoverSingle(target.getBusinessKey()));
				success++;
			} catch (Exception e) {
				failed++;
				log.error("낙찰 최종결제 Saga 재개 실패: auctionId={}, error={}", target.getBusinessKey(), e.getMessage());
			}
		}

		log.info("낙찰 최종결제 Saga 재개 배치 완료: 대상={}, 성공={}, 실패={}", targets.size(), success, failed);
	}

	private void runInNewTransaction(Runnable task) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.executeWithoutResult(status -> task.run());
	}

	public void recoverSingle(String sagaBusinessKey) {
		PaymentSagaExecution saga = sagaRepository.findBySagaTypeAndBusinessKeyForUpdate(
				PaymentSagaType.AUCTION_FINAL_PAYMENT, sagaBusinessKey)
			.orElseThrow(() -> new CustomException(ErrorType.PAYMENT_NOT_FOUND));

		if (!isRecoverableStatus(saga)) {
			return;
		}
		if (!isDueForRecovery(saga)) {
			return;
		}

		Long auctionId = Long.parseLong(sagaBusinessKey);
		AuctionFinalPaymentSagaStep failedStep = resolveStepOrDefault(saga.getFailedStep());
		AuctionFinalPaymentSagaStep checkpoint = resolveStepOrDefault(saga.getCheckpointStep());

		String sagaCommandId = sagaTracker.startOrResume(saga, checkpoint);

		try {
			AuctionOrderDto order = auctionOrderApiClient.getOrder(auctionId);
			PaymentMember seller = paymentSupport.findMemberById(order.sellerId());
			boolean settlementExists = paymentCreateSettlementUseCase.hasCompletePaymentSources(
				auctionId, order.productName(), seller, order.finalPrice());

			if (settlementExists && ORDER_STATUS_SUCCESS.equals(order.status())) {
				log.info("낙찰 최종결제 Saga 재개 스킵(이미 로컬 정산 완료): auctionId={}", auctionId);
				sagaTracker.markCompleted(PaymentSagaType.AUCTION_FINAL_PAYMENT, sagaBusinessKey,
					AuctionFinalPaymentSagaStep.COMPLETED);
				return;
			}

			if (ORDER_STATUS_PROCESSING.equals(order.status())) {
				replayFinalPayment(saga, order, sagaBusinessKey, checkpoint, true, sagaCommandId);
				return;
			}

			if (ORDER_STATUS_SUCCESS.equals(order.status())) {
				replayFinalPayment(saga, order, sagaBusinessKey, checkpoint, false, sagaCommandId);
				return;
			}

			throw new CustomException(ErrorType.PAYMENT_SAGA_RECOVERY_INVALID_ORDER_STATUS);
		} catch (Exception ex) {
			sagaTracker.markFailed(saga, failedStep, ex);
			throw ex;
		}
	}

	private void replayFinalPayment(PaymentSagaExecution saga, AuctionOrderDto order, String sagaBusinessKey,
		AuctionFinalPaymentSagaStep checkpoint,
		boolean completeRemoteOrder,
		String sagaCommandId) {
		Long auctionId = order.auctionId();
		Long buyerId = order.bidderId();
		int finalPrice = order.finalPrice();

		Deposit deposit = findDepositForRecovery(buyerId, auctionId);
		int depositAmount = deposit.getAmount();
		int paymentAmount = finalPrice - depositAmount;

		Wallet wallet = paymentSupport.findWalletByMemberIdForUpdate(buyerId);
		PaymentMember buyer = paymentSupport.findMemberById(buyerId);
		PaymentMember seller = paymentSupport.findMemberById(order.sellerId());

		if (!hasReached(checkpoint, AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE)) {
			if (deposit.getStatus() == DepositStatus.USED) {
				// 체크포인트 기록이 누락됐지만 로컬 차감은 커밋된 상태로 간주하고 다음 단계로 진행한다.
				log.warn("낙찰 최종결제 Saga 재개 시 보증금이 이미 USED 상태입니다. LOCAL_DEBIT_DONE으로 보정합니다. auctionId={}",
					auctionId);
				safeMarkStep(saga, sagaBusinessKey, AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE);
				safeMarkCheckpoint(saga, sagaBusinessKey, AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE);
			} else if (deposit.getStatus() != DepositStatus.HOLD) {
				throw new CustomException(ErrorType.PAYMENT_SAGA_RECOVERY_INVALID_DEPOSIT_STATE);
			} else {
				deposit.use();
				wallet.useDeposit(depositAmount);
				recordTransaction(buyer, wallet, WalletTransactionType.DEPOSIT_USED,
					-depositAmount, -depositAmount, ReferenceType.DEPOSIT, deposit.getId());

				wallet.pay(paymentAmount);
				recordTransaction(buyer, wallet, WalletTransactionType.AUCTION_PAYMENT,
					-paymentAmount, 0, ReferenceType.AUCTION_ORDER, order.orderId());
				safeMarkStep(saga, sagaBusinessKey, AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE);
				safeMarkCheckpoint(saga, sagaBusinessKey, AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE);
			}
		}

		if (completeRemoteOrder && !hasReached(checkpoint, AuctionFinalPaymentSagaStep.AUCTION_COMPLETE_SENT)) {
			auctionOrderApiClient.completeOrder(auctionId, sagaCommandId);
			safeMarkStep(saga, sagaBusinessKey, AuctionFinalPaymentSagaStep.AUCTION_COMPLETE_SENT);
		}

		// 체크포인트만 믿지 않고 두 지급 원천을 검증하여 누락된 유형만 복구한다.
		paymentCreateSettlementUseCase.createForPayment(auctionId, order.productName(), seller, finalPrice);
		if (!hasReached(checkpoint, AuctionFinalPaymentSagaStep.SETTLEMENT_READY)) {
			safeMarkStep(saga, sagaBusinessKey, AuctionFinalPaymentSagaStep.SETTLEMENT_READY);
			safeMarkCheckpoint(saga, sagaBusinessKey, AuctionFinalPaymentSagaStep.SETTLEMENT_READY);
		}

		AuctionPaymentCompletedEvent event = new AuctionPaymentCompletedEvent(
			order.orderId(),
			auctionId,
			order.sellerId(),
			buyerId,
			order.productName(),
			finalPrice
		);
		if (!hasReached(checkpoint, AuctionFinalPaymentSagaStep.COMPLETED)) {
			outboxUseCase.saveOutbox(event);
		}
		safeMarkCompleted(saga, sagaBusinessKey);

		log.info("낙찰 최종결제 Saga 재개 성공: auctionId={}, remoteCompleteCalled={}, finalPrice={}",
			auctionId, completeRemoteOrder, finalPrice);
	}

	private Deposit findDepositForRecovery(Long memberId, Long auctionId) {
		return depositRepository.findByMemberIdAndAuctionId(memberId, auctionId)
			.orElseThrow(() -> new CustomException(ErrorType.PAYMENT_SAGA_RECOVERY_DEPOSIT_NOT_FOUND));
	}

	private boolean hasReached(AuctionFinalPaymentSagaStep checkpoint, AuctionFinalPaymentSagaStep target) {
		return checkpoint.ordinal() >= target.ordinal();
	}

	private boolean isRecoverableStatus(PaymentSagaExecution saga) {
		return saga.getStatus() == PaymentSagaExecutionStatus.FAILED
			|| saga.getStatus() == PaymentSagaExecutionStatus.IN_PROGRESS;
	}

	private boolean isDueForRecovery(PaymentSagaExecution saga) {
		LocalDateTime now = LocalDateTime.now();
		if (saga.getStatus() == PaymentSagaExecutionStatus.FAILED) {
			return saga.isRetryable() && saga.getNextRetryAt() != null && !saga.getNextRetryAt().isAfter(now);
		}
		if (saga.getStatus() == PaymentSagaExecutionStatus.IN_PROGRESS) {
			return saga.getLastAttemptAt() != null
				&& !saga.getLastAttemptAt().isAfter(now.minusSeconds(inProgressTimeoutSeconds));
		}
		return false;
	}

	private List<PaymentSagaExecution> mergeTargets(List<PaymentSagaExecution> failedTargets,
		List<PaymentSagaExecution> staleInProgressTargets, int limit) {
		Map<String, PaymentSagaExecution> merged = new LinkedHashMap<>();
		for (PaymentSagaExecution target : failedTargets) {
			merged.put(target.getBusinessKey(), target);
			if (merged.size() >= limit) {
				return new ArrayList<>(merged.values());
			}
		}
		for (PaymentSagaExecution target : staleInProgressTargets) {
			merged.putIfAbsent(target.getBusinessKey(), target);
			if (merged.size() >= limit) {
				break;
			}
		}
		return new ArrayList<>(merged.values());
	}

	private AuctionFinalPaymentSagaStep resolveStepOrDefault(String stepName) {
		if (stepName == null || stepName.isBlank()) {
			return AuctionFinalPaymentSagaStep.INITIATED;
		}
		try {
			return AuctionFinalPaymentSagaStep.valueOf(stepName);
		} catch (IllegalArgumentException e) {
			log.warn("유효하지 않은 Saga step 값을 기본값으로 대체합니다. stepName={}, defaultStep={}", stepName,
				AuctionFinalPaymentSagaStep.INITIATED);
			return AuctionFinalPaymentSagaStep.INITIATED;
		}
	}

	private void recordTransaction(PaymentMember member, Wallet wallet,
		WalletTransactionType type, int balanceDelta, int holdingDelta,
		ReferenceType refType, Long refId) {
		PaymentTransaction transaction = PaymentTransaction.builder()
			.member(member)
			.wallet(wallet)
			.transactionType(type)
			.balanceDelta(balanceDelta)
			.holdingDelta(holdingDelta)
			.balanceAfter(wallet.getBalance())
			.referenceType(refType)
			.referenceId(refId)
			.build();
		transactionRepository.save(transaction);
	}

	private void safeMarkStep(PaymentSagaExecution saga, String sagaBusinessKey, AuctionFinalPaymentSagaStep step) {
		try {
			sagaTracker.markStep(saga, step);
		} catch (Exception e) {
			log.error("낙찰 최종결제 Saga 재개 단계 기록 실패: auctionId={}, step={}, error={}",
				sagaBusinessKey, step, e.getMessage());
		}
	}

	private void safeMarkCheckpoint(PaymentSagaExecution saga, String sagaBusinessKey,
		AuctionFinalPaymentSagaStep step) {
		try {
			sagaTracker.markCheckpoint(saga, step);
		} catch (Exception e) {
			log.error("낙찰 최종결제 Saga 재개 체크포인트 기록 실패: auctionId={}, step={}, error={}",
				sagaBusinessKey, step, e.getMessage());
		}
	}

	private void safeMarkCompleted(PaymentSagaExecution saga, String sagaBusinessKey) {
		try {
			sagaTracker.markCompleted(saga, AuctionFinalPaymentSagaStep.COMPLETED);
		} catch (Exception e) {
			log.error("낙찰 최종결제 Saga 재개 완료 기록 실패: auctionId={}, step={}, error={}",
				sagaBusinessKey, AuctionFinalPaymentSagaStep.COMPLETED, e.getMessage());
		}
	}
}
