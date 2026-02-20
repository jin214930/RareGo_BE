package com.bugzero.rarego.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Deposit;
import com.bugzero.rarego.domain.DepositStatus;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.DepositRepository;
import com.bugzero.rarego.shared.auction.dto.AuctionOrderDto;
import com.bugzero.rarego.shared.payment.event.AuctionPaymentExpiringSoonEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentAuctionExpiringSoonUseCase {
	private final DepositRepository depositRepository;
	private final OutboxUseCase outboxUseCase;

	@Transactional
	public void publishExpiringSoonEvent(AuctionOrderDto order, LocalDateTime expiredAt) {
		Deposit deposit = depositRepository.findByMemberIdAndAuctionId(order.bidderId(), order.auctionId())
			.filter(d -> d.getStatus() == DepositStatus.HOLD)
			.orElseThrow(() -> new CustomException(ErrorType.DEPOSIT_NOT_FOUND));

		int depositAmount = deposit.getAmount();
		int paymentAmount = order.finalPrice() - depositAmount;

		AuctionPaymentExpiringSoonEvent event = new AuctionPaymentExpiringSoonEvent(
			order.orderId(),
			order.auctionId(),
			order.bidderId(),
			order.sellerId(),
			order.productName(),
			paymentAmount,
			expiredAt
		);

		outboxUseCase.saveOutbox(event);
	}
}
