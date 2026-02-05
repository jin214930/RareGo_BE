package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.in.dto.WalletResponseDto;

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
