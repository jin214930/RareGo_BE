package com.bugzero.rarego.bounded_context.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.domain.Account;
import com.bugzero.rarego.domain.AuthRole;
import com.bugzero.rarego.domain.Provider;
import com.bugzero.rarego.domain.TokenPairDto;
import com.bugzero.rarego.config.JwtProperties;
import com.bugzero.rarego.app.AuthAccessTokenBlacklistUseCase;
import com.bugzero.rarego.app.AuthIssueTokenUseCase;
import com.bugzero.rarego.app.AuthRefreshTokenFacade;
import com.bugzero.rarego.app.RefreshTokenStore;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.global.security.JwtParser;
import com.bugzero.rarego.out.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AuthRefreshTokenFacadeTest {
	@Mock
	private JwtParser jwtParser;

	@Mock
	private AuthIssueTokenUseCase authIssueTokenUseCase;

	@Mock
	private AuthAccessTokenBlacklistUseCase authAccessTokenBlacklistUseCase;

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private JwtProperties jwtProperties;

	@InjectMocks
	private AuthRefreshTokenFacade authRefreshTokenFacade;

	@Test
	@DisplayName("refresh token이 없으면 AUTH_REFRESH_TOKEN_REQUIRED 예외가 발생한다.")
	void refreshFailsWhenRefreshTokenMissing() {
		// given
		String refreshToken = " ";
		String accessToken = "access-token";

		// when
		Throwable thrown = catchThrowable(() -> authRefreshTokenFacade.refresh(refreshToken, accessToken));

		// then
		assertThat(thrown)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_REQUIRED);
		verifyNoInteractions(
			jwtParser,
			authIssueTokenUseCase,
			authAccessTokenBlacklistUseCase,
			accountRepository,
			refreshTokenStore,
			jwtProperties
		);
	}

	@Test
	@DisplayName("refresh token 파싱에 실패하면 AUTH_REFRESH_TOKEN_INVALID 예외가 발생한다.")
	void refreshFailsWhenParseRefreshPublicIdReturnsNull() {
		// given
		String refreshToken = "refresh-token";
		String accessToken = "access-token";
		when(jwtParser.parseRefreshPublicId(refreshToken)).thenReturn(null);

		// when
		Throwable thrown = catchThrowable(() -> authRefreshTokenFacade.refresh(refreshToken, accessToken));

		// then
		assertThat(thrown)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
		verify(jwtParser).parseRefreshPublicId(refreshToken);
		verifyNoInteractions(refreshTokenStore, accountRepository, authIssueTokenUseCase, authAccessTokenBlacklistUseCase);
	}

	@Test
	@DisplayName("redis에 refresh token이 유효하지 않으면 AUTH_REFRESH_TOKEN_INVALID 예외가 발생한다.")
	void refreshFailsWhenRefreshTokenIsInvalidInStore() {
		// given
		String refreshToken = "refresh-token";
		String accessToken = "access-token";
		String memberPublicId = "member-public-id";
		when(jwtParser.parseRefreshPublicId(refreshToken)).thenReturn(memberPublicId);
		when(refreshTokenStore.isValid(refreshToken, memberPublicId)).thenReturn(false);

		// when
		Throwable thrown = catchThrowable(() -> authRefreshTokenFacade.refresh(refreshToken, accessToken));

		// then
		assertThat(thrown)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
		verify(jwtParser).parseRefreshPublicId(refreshToken);
		verify(refreshTokenStore).isValid(refreshToken, memberPublicId);
		verifyNoInteractions(accountRepository, authIssueTokenUseCase, authAccessTokenBlacklistUseCase);
	}

	@Test
	@DisplayName("계정을 찾지 못하면 AUTH_REFRESH_TOKEN_INVALID 예외가 발생한다.")
	void refreshFailsWhenAccountMissing() {
		// given
		String refreshToken = "refresh-token";
		String accessToken = "access-token";
		String memberPublicId = "member-public-id";
		when(jwtParser.parseRefreshPublicId(refreshToken)).thenReturn(memberPublicId);
		when(refreshTokenStore.isValid(refreshToken, memberPublicId)).thenReturn(true);
		when(accountRepository.findByMemberPublicId(memberPublicId)).thenReturn(Optional.empty());

		// when
		Throwable thrown = catchThrowable(() -> authRefreshTokenFacade.refresh(refreshToken, accessToken));

		// then
		assertThat(thrown)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
		verify(accountRepository).findByMemberPublicId(memberPublicId);
		verifyNoInteractions(authIssueTokenUseCase, authAccessTokenBlacklistUseCase);
	}

	@Test
	@DisplayName("탈퇴 계정이면 refresh token을 폐기하고 AUTH_ACCOUNT_DELETED 예외가 발생한다.")
	void refreshFailsWhenAccountDeleted() {
		// given
		String refreshToken = "refresh-token";
		String accessToken = "access-token";
		String memberPublicId = "member-public-id";
		Account deletedAccount = Account.builder()
			.memberPublicId(memberPublicId)
			.role(AuthRole.USER)
			.provider(Provider.GOOGLE)
			.providerId("provider-id")
			.build();
		deletedAccount.softDelete();

		when(jwtParser.parseRefreshPublicId(refreshToken)).thenReturn(memberPublicId);
		when(refreshTokenStore.isValid(refreshToken, memberPublicId)).thenReturn(true);
		when(accountRepository.findByMemberPublicId(memberPublicId)).thenReturn(Optional.of(deletedAccount));

		// when
		Throwable thrown = catchThrowable(() -> authRefreshTokenFacade.refresh(refreshToken, accessToken));

		// then
		assertThat(thrown)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_ACCOUNT_DELETED);
		verify(refreshTokenStore).revoke(refreshToken);
		verifyNoInteractions(authIssueTokenUseCase, authAccessTokenBlacklistUseCase, jwtProperties);
	}

	@Test
	@DisplayName("refresh 성공 시 기존 refresh를 폐기하고 새 토큰을 발급/저장하고 access token을 블랙리스트에 추가한다.")
	void refreshSucceedsAndRotatesRefreshToken() {
		// given
		String refreshToken = "refresh-token";
		String accessToken = "access-token";
		String memberPublicId = "member-public-id";
		String role = AuthRole.USER.name();
		Account account = Account.builder()
			.memberPublicId(memberPublicId)
			.role(AuthRole.USER)
			.provider(Provider.GOOGLE)
			.providerId("google-123")
			.build();

		when(jwtParser.parseRefreshPublicId(refreshToken)).thenReturn(memberPublicId);
		when(refreshTokenStore.isValid(refreshToken, memberPublicId)).thenReturn(true);
		when(accountRepository.findByMemberPublicId(memberPublicId)).thenReturn(Optional.of(account));
		when(authIssueTokenUseCase.issueToken(memberPublicId, role, true)).thenReturn("new-access");
		when(authIssueTokenUseCase.issueToken(memberPublicId, role, false)).thenReturn("new-refresh");
		when(jwtProperties.getAccessTokenExpireSeconds()).thenReturn(3600);

		// when
		TokenPairDto result = authRefreshTokenFacade.refresh(refreshToken, accessToken);

		// then
		assertThat(result.accessToken()).isEqualTo("new-access");
		assertThat(result.refreshToken()).isEqualTo("new-refresh");
		verify(refreshTokenStore).revoke(refreshToken);
		verify(authIssueTokenUseCase).issueToken(memberPublicId, role, true);
		verify(authIssueTokenUseCase).issueToken(memberPublicId, role, false);
		verify(refreshTokenStore).save("new-refresh", memberPublicId, 3600);
		verify(authAccessTokenBlacklistUseCase).blacklist(accessToken);
	}
}
