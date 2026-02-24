package com.bugzero.rarego.bounded_context.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.bugzero.rarego.app.AuthJoinAccountUseCase;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.MemberJoinResilienceClient;
import com.bugzero.rarego.domain.Account;
import com.bugzero.rarego.domain.AccountStatus;
import com.bugzero.rarego.domain.AuthRole;
import com.bugzero.rarego.domain.Provider;
import com.bugzero.rarego.out.AccountRepository;
import com.bugzero.rarego.shared.member.domain.MemberJoinResponseDto;

@ExtendWith(MockitoExtension.class)
class AuthJoinAccountUseCaseTest {
	@Mock
	private AccountRepository accountRepository;

	@Mock
	private MemberJoinResilienceClient memberJoinResilienceClient;

	@InjectMocks
	private AuthJoinAccountUseCase authJoinAccountUseCase;

	@Test
	@DisplayName("가입 시 provider/providerId로 계정을 생성하고 USER 역할로 저장한다.")
	void joinSavesAccountWithUserRole() {
		when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(memberJoinResilienceClient.join(eq("test@example.com"), anyString()))
			.thenAnswer(invocation -> new MemberJoinResponseDto("tester", invocation.getArgument(1)));

		Account result = authJoinAccountUseCase.join(Provider.GOOGLE, "google-123", "test@example.com");

		ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
		verify(accountRepository, atLeastOnce()).save(captor.capture());
		List<Account> savedAccounts = captor.getAllValues();
		Account saved = savedAccounts.get(savedAccounts.size() - 1);
		ArgumentCaptor<String> memberPublicIdCaptor = ArgumentCaptor.forClass(String.class);
		verify(memberJoinResilienceClient).join(eq("test@example.com"), memberPublicIdCaptor.capture());
		String memberPublicId = memberPublicIdCaptor.getValue();

		assertThat(saved.getProvider()).isEqualTo(Provider.GOOGLE);
		assertThat(saved.getProviderId()).isEqualTo("google-123");
		assertThat(saved.getRole()).isEqualTo(AuthRole.USER);
		assertThatCode(() -> UUID.fromString(memberPublicId)).doesNotThrowAnyException();
		assertThat(saved.getMemberPublicId()).isEqualTo(memberPublicId);
		assertThat(result).isEqualTo(saved);
	}

	@Test
	@DisplayName("중복 저장이 발생하면 기존 계정을 조회해 반환한다.")
	void joinReturnsExistingAccountWhenDuplicate() {
		Account existing = Account.builder()
			.provider(Provider.NAVER)
			.providerId("naver-456")
			.memberPublicId("1e2c1e52-7e77-4f5d-8c4f-1a2a12b7f9aa")
			.role(AuthRole.USER)
			.build();

		when(accountRepository.save(any(Account.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate"));
		when(accountRepository.findByProviderAndProviderId(Provider.NAVER, "naver-456"))
			.thenReturn(Optional.of(existing));

		Account result = authJoinAccountUseCase.join(Provider.NAVER, "naver-456", "naver@example.com");

		assertThat(result).isEqualTo(existing);
		verify(accountRepository).save(any(Account.class));
		verify(accountRepository).findByProviderAndProviderId(Provider.NAVER, "naver-456");
		verify(memberJoinResilienceClient, never()).join(anyString(), anyString());
	}

	@Test
	@DisplayName("멤버 가입이 실패하면 MEMBER_JOIN_FAILED로 반환하고 계정을 PENDING으로 유지한다.")
	void joinThrowsMemberJoinFailedWhenMemberJoinFails() {
		when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(memberJoinResilienceClient.join(eq("kakao@example.com"), anyString()))
			.thenThrow(new RuntimeException("boom"));

		assertThatThrownBy(() -> authJoinAccountUseCase.join(Provider.KAKAO, "kakao-000", "kakao@example.com"))
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.MEMBER_JOIN_FAILED);

		ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
		verify(accountRepository, atLeast(2)).save(captor.capture());
		List<Account> savedAccounts = captor.getAllValues();
		Account lastSaved = savedAccounts.get(savedAccounts.size() - 1);
		assertThat(lastSaved.getStatus()).isEqualTo(AccountStatus.PENDING);
	}

	@Test
	@DisplayName("멤버가 기존에 존재해 다른 publicId를 반환하면 계정의 publicId를 교체한다.")
	void joinReplacesMemberPublicIdWhenMemberReturnsDifferentId() {
		when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(accountRepository.findByMemberPublicId(anyString())).thenReturn(Optional.empty());

		AtomicReference<String> responsePublicId = new AtomicReference<>();
		when(memberJoinResilienceClient.join(eq("test@example.com"), anyString()))
			.thenAnswer(invocation -> {
				String requested = invocation.getArgument(1);
				String generated;
				do {
					generated = UUID.randomUUID().toString();
				} while (generated.equals(requested));
				responsePublicId.set(generated);
				return new MemberJoinResponseDto("tester", generated);
			});

		Account result = authJoinAccountUseCase.join(Provider.GOOGLE, "google-123", "test@example.com");

		ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
		verify(accountRepository, atLeast(2)).save(captor.capture());
		List<Account> savedAccounts = captor.getAllValues();
		Account lastSaved = savedAccounts.get(savedAccounts.size() - 1);

		assertThat(responsePublicId.get()).isNotNull();
		assertThat(lastSaved.getMemberPublicId()).isEqualTo(responsePublicId.get());
		assertThat(lastSaved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
		assertThat(result.getMemberPublicId()).isEqualTo(responsePublicId.get());
		verify(accountRepository).findByMemberPublicId(responsePublicId.get());
	}
}
