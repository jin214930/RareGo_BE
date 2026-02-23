package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.PaymentTransaction;
import com.bugzero.rarego.domain.ReferenceType;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.domain.WalletTransactionType;
import com.bugzero.rarego.in.dto.WithdrawRequestDto;
import com.bugzero.rarego.out.PaymentTransactionRepository;
import com.bugzero.rarego.shared.auction.out.AuctionApiClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentWithdrawUseCase {
	private final PaymentSupport paymentSupport;
	private final PaymentTransactionRepository paymentTransactionRepository;

	private final AuctionApiClient auctionApiClient;

	public boolean hasProcessingOrders(String publicId) {
		return auctionApiClient.hasProcessingOrders(publicId);
	}

	@Transactional
	public void withdraw(String memberPublicId, WithdrawRequestDto request) {
		PaymentMember member = paymentSupport.findMemberByPublicId(memberPublicId);
		Wallet wallet = paymentSupport.findWalletByMemberIdForUpdate(member.getId());
		int amount = request.amount();

		wallet.withdraw(amount);

		PaymentTransaction transaction = PaymentTransaction.builder()
			.member(member)
			.wallet(wallet)
			.transactionType(WalletTransactionType.WITHDRAW_DONE)
			.balanceDelta(-amount)
			.holdingDelta(0)
			.balanceAfter(wallet.getBalance())
			.referenceType(ReferenceType.WITHDRAW)
			.referenceId(0L) // 참조 id가 없음, nullable 고려
			.build();

		paymentTransactionRepository.save(transaction);
	}
}
