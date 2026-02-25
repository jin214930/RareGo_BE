package com.bugzero.rarego.app;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bugzero.rarego.domain.AuctionFinalPaymentSagaStep;
import com.bugzero.rarego.domain.Deposit;
import com.bugzero.rarego.domain.DepositStatus;
import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.PaymentSagaType;
import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.ReferenceType;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.in.dto.AuctionFinalPaymentRequestDto;
import com.bugzero.rarego.in.dto.AuctionFinalPaymentResponseDto;
import com.bugzero.rarego.out.AuctionOrderApiClient;
import com.bugzero.rarego.out.DepositRepository;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.shared.auction.dto.AuctionOrderDto;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentAuctionFinalUseCase {
	private final AuctionOrderApiClient auctionOrderApiClient;
	private final DepositRepository depositRepository;
	private final PaymentTransactionRepository transactionRepository;
	private final SettlementRepository settlementRepository;
	private final PaymentSupport paymentSupport;
	private final OutboxUseCase outboxUseCase;
	private final PaymentSagaTracker sagaTracker;

	@Value("${auction.payment-timeout-days:3}")
	private int paymentTimeoutDays;

	@Transactional
	public AuctionFinalPaymentResponseDto finalPayment(String memberPublicId, Long auctionId,
		AuctionFinalPaymentRequestDto request) {
		AuctionFinalPaymentSagaStep failedStep = null;
		String sagaBusinessKey = String.valueOf(auctionId);
		String sagaCommandId = null;

		try {
			Long memberId = paymentSupport.findMemberByPublicId(memberPublicId).getId();

			// TODO: 배송 로직 구현 시 request(배송 정보)를 사용하여 배송 정보 저장 필요
			// 1. 주문 조회 및 검증 (Port를 통해 Auction 모듈 접근)
			AuctionOrderDto order = findAndValidateOrder(auctionId, memberId);

			sagaCommandId = sagaTracker.startOrResume(PaymentSagaType.AUCTION_FINAL_PAYMENT, sagaBusinessKey,
				AuctionFinalPaymentSagaStep.INITIATED);
			failedStep = AuctionFinalPaymentSagaStep.INITIATED;

			// 2. 보증금 조회
			Deposit deposit = findDeposit(memberId, auctionId);

			// 3. 금액 계산
			int finalPrice = order.finalPrice();
			int depositAmount = deposit.getAmount();
			int paymentAmount = finalPrice - depositAmount;

			// 4. 지갑 조회
			Wallet wallet = paymentSupport.findWalletByMemberIdForUpdate(memberId);
			PaymentMember buyer = paymentSupport.findMemberById(memberId);

			// 5. 보증금 사용 처리
			failedStep = AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE;
			deposit.use();
			wallet.useDeposit(depositAmount);
			recordTransaction(buyer, wallet, WalletTransactionType.DEPOSIT_USED,
				-depositAmount, -depositAmount, ReferenceType.DEPOSIT, deposit.getId());

			// 6. 잔금 결제 처리
			wallet.pay(paymentAmount);
			recordTransaction(buyer, wallet, WalletTransactionType.AUCTION_PAYMENT,
				-paymentAmount, 0, ReferenceType.AUCTION_ORDER, order.orderId());
			safeMarkStep(sagaBusinessKey, AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE);
			registerAfterCommitCheckpoint(sagaBusinessKey, AuctionFinalPaymentSagaStep.LOCAL_DEBIT_DONE);

			// 7. 주문 완료 처리 (Client를 통해 Auction 모듈에 요청)
			failedStep = AuctionFinalPaymentSagaStep.AUCTION_COMPLETE_SENT;
			auctionOrderApiClient.completeOrder(auctionId, sagaCommandId);
			safeMarkStep(sagaBusinessKey, AuctionFinalPaymentSagaStep.AUCTION_COMPLETE_SENT);

			// 8. 정산 정보 생성 (status = READY)
			failedStep = AuctionFinalPaymentSagaStep.SETTLEMENT_READY;
			PaymentMember seller = paymentSupport.findMemberById(order.sellerId());
			Settlement settlement = Settlement.create(auctionId, order.productName(), seller, finalPrice);
			settlementRepository.save(settlement);
			safeMarkStep(sagaBusinessKey, AuctionFinalPaymentSagaStep.SETTLEMENT_READY);
			registerAfterCommitCheckpoint(sagaBusinessKey, AuctionFinalPaymentSagaStep.SETTLEMENT_READY);

			log.info("낙찰 결제 완료: auctionId={}, memberId={}, finalPrice={}, paid={}, settlementId={}",
				auctionId, memberId, finalPrice, paymentAmount, settlement.getId());

			// 9. 낙찰 결제 완료 이벤트 발행
			failedStep = AuctionFinalPaymentSagaStep.COMPLETED;
			AuctionPaymentCompletedEvent event = new AuctionPaymentCompletedEvent(
				order.orderId(),
				auctionId,
				order.sellerId(),
				memberId,
				order.productName(),
				finalPrice);
			outboxUseCase.saveOutbox(event);
			safeMarkStep(sagaBusinessKey, AuctionFinalPaymentSagaStep.COMPLETED);
			registerAfterCommitCompleted(sagaBusinessKey, AuctionFinalPaymentSagaStep.COMPLETED);

			return AuctionFinalPaymentResponseDto.of(
				order.orderId(),
				auctionId,
				buyer.getPublicId(),
				finalPrice,
				depositAmount,
				wallet.getBalance(),
				LocalDateTime.now());
		} catch (Exception ex) {
			if (failedStep != null) {
				try {
					sagaTracker.markFailed(PaymentSagaType.AUCTION_FINAL_PAYMENT, sagaBusinessKey, failedStep, ex);
				} catch (Exception sagaEx) {
					log.error("낙찰 최종결제 Saga 실패 기록 중 추가 오류 발생: auctionId={}, failedStep={}, error={}",
						auctionId, failedStep, sagaEx.getMessage());
				}
			}
			throw ex;
		}
	}

	private AuctionOrderDto findAndValidateOrder(Long auctionId, Long memberId) {
		AuctionOrderDto order = auctionOrderApiClient.getOrder(auctionId);

		if (!order.bidderId().equals(memberId)) {
			throw new CustomException(ErrorType.NOT_AUCTION_WINNER);
		}

		if (!"PROCESSING".equals(order.status())) {
			throw new CustomException(ErrorType.INVALID_ORDER_STATUS);
		}

		// 결제 기한 검증
		LocalDateTime deadline = order.createdAt().plusDays(paymentTimeoutDays);
		if (LocalDateTime.now().isAfter(deadline)) {
			throw new CustomException(ErrorType.PAYMENT_DEADLINE_EXCEEDED);
		}

		return order;
	}

	private Deposit findDeposit(Long memberId, Long auctionId) {
		return depositRepository.findByMemberIdAndAuctionId(memberId, auctionId)
			.filter(d -> d.getStatus() == DepositStatus.HOLD)
			.orElseThrow(() -> new CustomException(ErrorType.DEPOSIT_NOT_FOUND));
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

	private void safeMarkStep(String sagaBusinessKey, AuctionFinalPaymentSagaStep step) {
		try {
			sagaTracker.markStep(PaymentSagaType.AUCTION_FINAL_PAYMENT, sagaBusinessKey, step);
		} catch (Exception e) {
			log.error("낙찰 최종결제 Saga 단계 기록 실패(비즈니스 로직은 계속 진행): auctionId={}, step={}, error={}",
				sagaBusinessKey, step, e.getMessage());
		}
	}

	private void registerAfterCommitCheckpoint(String sagaBusinessKey, AuctionFinalPaymentSagaStep step) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					sagaTracker.markCheckpoint(PaymentSagaType.AUCTION_FINAL_PAYMENT, sagaBusinessKey, step);
				} catch (Exception e) {
					log.error("낙찰 최종결제 Saga 체크포인트 기록 실패: auctionId={}, step={}, error={}",
						sagaBusinessKey, step, e.getMessage());
				}
			}
		});
	}

	private void registerAfterCommitCompleted(String sagaBusinessKey, AuctionFinalPaymentSagaStep step) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					sagaTracker.markCompleted(PaymentSagaType.AUCTION_FINAL_PAYMENT, sagaBusinessKey, step);
				} catch (Exception e) {
					log.error("낙찰 최종결제 Saga 완료 기록 실패: auctionId={}, step={}, error={}",
						sagaBusinessKey, step, e.getMessage());
				}
			}
		});
	}
}
