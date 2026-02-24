package com.bugzero.rarego.app;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.domain.Account;
import com.bugzero.rarego.domain.AccountStatus;
import com.bugzero.rarego.domain.AuthRole;
import com.bugzero.rarego.domain.Provider;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.AccountRepository;
import com.bugzero.rarego.out.MemberJoinResilienceClient;
import com.bugzero.rarego.shared.member.domain.MemberJoinResponseDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthJoinAccountUseCase {
	private final AccountRepository accountRepository;
	private final MemberJoinResilienceClient memberJoinResilienceClient;

	public Account join(Provider provider, String providerId, String email) {
		Account account = createPendingAccount(provider, providerId);
		return handleJoin(account, email);
	}

	public Account completePending(Account account, String email) {
		return handleJoin(account, email);
	}

	private Account handleJoin(Account account, String email) {
		if (account.getStatus() == AccountStatus.ACTIVE) {
			return account;
		}
		return activateAccount(account, email);
	}

	private Account createPendingAccount(Provider provider, String providerId) {
		try {
			Account account = Account.builder()
				.memberPublicId(UUID.randomUUID().toString())
				.status(AccountStatus.PENDING)
				.role(AuthRole.USER)
				.provider(provider)
				.providerId(providerId)
				.build();
			return accountRepository.save(account);
		} catch (DataIntegrityViolationException e) {
			// 동시성 문제로 이미 만들어졌다면 다시 조회해서 반환
			return accountRepository.findByProviderAndProviderId(provider, providerId)
				.orElseThrow(() -> e);
		}
	}

	private Account activateAccount(Account account, String email) {
		if (email == null || email.isBlank()) {
			throw new CustomException(ErrorType.AUTH_JOIN_FAILED);
		}
		accountRepository.save(account);
		try {
			MemberJoinResponseDto memberResponse = joinMember(email, account.getMemberPublicId());
			validateMemberJoinResponse(memberResponse, account.getMemberPublicId());
			account.markActive();
			return accountRepository.save(account);
		} catch (RuntimeException e) {
			account.markPending();
			accountRepository.save(account);
			throw e;
		}
	}

	private MemberJoinResponseDto joinMember(String email, String memberPublicId) {
		try {
			return memberJoinResilienceClient.join(email, memberPublicId);
		} catch (RuntimeException e) {
			throw mapJoinException(e);
		}
	}

	private void validateMemberJoinResponse(MemberJoinResponseDto response, String expectedMemberPublicId) {
		if (response == null || response.memberPublicId() == null || response.memberPublicId().isBlank()) {
			throw new CustomException(ErrorType.AUTH_JOIN_FAILED);
		}
		if (!expectedMemberPublicId.equals(response.memberPublicId())) {
			throw new CustomException(ErrorType.AUTH_JOIN_FAILED);
		}
	}

	private RuntimeException mapJoinException(Throwable throwable) {
		if (throwable instanceof CustomException customException) {
			return customException;
		}
		if (throwable.getCause() instanceof CustomException customException) {
			return customException;
		}
		return new CustomException(ErrorType.MEMBER_JOIN_FAILED);
	}
}
