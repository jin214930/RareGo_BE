package com.bugzero.rarego.bounded_context.payment.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.bounded_context.payment.domain.PaymentMember;
import com.bugzero.rarego.bounded_context.payment.in.dto.WalletResponseDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentGetMyWalletUseCase {
	private final PaymentSupport paymentSupport;

	@Transactional(readOnly = true)
	public WalletResponseDto getMyWallet(String memberPublicId) {
		PaymentMember member = paymentSupport.findMemberByPublicId(memberPublicId);
		return WalletResponseDto.from(paymentSupport.findWalletByMember(member));
	}
}
