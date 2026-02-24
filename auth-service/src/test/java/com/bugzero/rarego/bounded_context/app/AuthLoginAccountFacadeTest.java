package com.bugzero.rarego.bounded_context.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.domain.Account;
import com.bugzero.rarego.domain.AccountStatus;
import com.bugzero.rarego.domain.AuthRole;
import com.bugzero.rarego.domain.Provider;
import com.bugzero.rarego.app.AuthJoinAccountUseCase;
import com.bugzero.rarego.app.AuthLoginAccountFacade;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AuthLoginAccountFacadeTest {
	@Mock
	private AccountRepository accountRepository;

	@Mock
	private AuthJoinAccountUseCase authJoinAccountUseCase;

	@InjectMocks
	private AuthLoginAccountFacade authLoginAccountFacade;

	@Test
	@DisplayName("기존 계정이 있으면 가입 없이 계정을 반환한다.")
	void loginUsesExistingAccount() {
		Account existing = Account.builder()
			.provider(Provider.GOOGLE)
			.providerId("google-123")
			.memberPublicId("550e8400-e29b-41d4-a716-446655440000")
			.role(AuthRole.USER)
			.build();

		when(accountRepository.findByProviderAndProviderId(Provider.GOOGLE, "google-123"))
			.thenReturn(Optional.of(existing));
		Account account = authLoginAccountFacade.loginOrSignup(
			"google-123",
			"test@example.com",
			Provider.GOOGLE
		);

		assertThat(account).isEqualTo(existing);
		verify(authJoinAccountUseCase, never()).join(any(), anyString(), anyString());
		verify(accountRepository).findByProviderAndProviderId(Provider.GOOGLE, "google-123");
	}

	@Test
	@DisplayName("계정이 없으면 가입 후 계정을 반환한다.")
	void loginCreatesAccountWhenMissing() {
		Account created = Account.builder()
			.provider(Provider.KAKAO)
			.providerId("kakao-456")
			.memberPublicId("1e2c1e52-7e77-4f5d-8c4f-1a2a12b7f9aa")
			.role(AuthRole.SELLER)
			.build();

		when(accountRepository.findByProviderAndProviderId(Provider.KAKAO, "kakao-456"))
			.thenReturn(Optional.empty());
		when(authJoinAccountUseCase.join(Provider.KAKAO, "kakao-456", "kakao@example.com"))
			.thenReturn(created);
		Account account = authLoginAccountFacade.loginOrSignup(
			"kakao-456",
			"kakao@example.com",
			Provider.KAKAO
		);

		assertThat(account).isEqualTo(created);
		verify(authJoinAccountUseCase).join(Provider.KAKAO, "kakao-456", "kakao@example.com");
	}

	@Test
	@DisplayName("PENDING 계정이면 가입 완료를 시도하고 완료된 계정을 반환한다.")
	void loginCompletesPendingAccount() {
		Account pending = Account.builder()
			.provider(Provider.GOOGLE)
			.providerId("google-999")
			.memberPublicId("pending-public-id")
			.status(AccountStatus.PENDING)
			.role(AuthRole.USER)
			.build();
		Account completed = Account.builder()
			.provider(Provider.GOOGLE)
			.providerId("google-999")
			.memberPublicId("pending-public-id")
			.status(AccountStatus.ACTIVE)
			.role(AuthRole.USER)
			.build();

		when(accountRepository.findByProviderAndProviderId(Provider.GOOGLE, "google-999"))
			.thenReturn(Optional.of(pending));
		when(authJoinAccountUseCase.completePending(pending, "pending@example.com"))
			.thenReturn(completed);

		Account account = authLoginAccountFacade.loginOrSignup(
			"google-999",
			"pending@example.com",
			Provider.GOOGLE
		);

		assertThat(account).isEqualTo(completed);
		verify(authJoinAccountUseCase).completePending(pending, "pending@example.com");
	}

	@Test
	@DisplayName("PENDING 계정의 가입 완료가 실패하면 로그인을 막는다.")
	void loginBlocksWhenPendingCompletionFails() {
		Account pending = Account.builder()
			.provider(Provider.NAVER)
			.providerId("naver-999")
			.memberPublicId("pending-public-id")
			.status(AccountStatus.PENDING)
			.role(AuthRole.USER)
			.build();

		when(accountRepository.findByProviderAndProviderId(Provider.NAVER, "naver-999"))
			.thenReturn(Optional.of(pending));
		when(authJoinAccountUseCase.completePending(pending, "pending@example.com"))
			.thenThrow(new CustomException(ErrorType.AUTH_JOIN_FAILED));

		assertThatThrownBy(() -> authLoginAccountFacade.loginOrSignup(
			"naver-999",
			"pending@example.com",
			Provider.NAVER
		))
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_JOIN_FAILED);

		verify(authJoinAccountUseCase).completePending(pending, "pending@example.com");
	}
}
