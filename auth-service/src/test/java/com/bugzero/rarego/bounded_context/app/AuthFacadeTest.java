package com.bugzero.rarego.bounded_context.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.bounded_context.auth.domain.Account;
import com.bugzero.rarego.bounded_context.auth.domain.AuthRole;
import com.bugzero.rarego.bounded_context.auth.domain.Provider;
import com.bugzero.rarego.bounded_context.auth.domain.TokenPairDto;
import com.bugzero.rarego.bounded_context.auth.app.AuthFacade;
import com.bugzero.rarego.bounded_context.auth.app.AuthIssueTokenUseCase;
import com.bugzero.rarego.bounded_context.auth.app.AuthLoginAccountFacade;
import com.bugzero.rarego.bounded_context.auth.app.AuthLogoutAccountUseCase;
import com.bugzero.rarego.bounded_context.auth.app.AuthRefreshTokenFacade;
import com.bugzero.rarego.bounded_context.auth.app.AuthStoreRefreshTokenUseCase;

@ExtendWith(MockitoExtension.class)
class AuthFacadeTest {
	@Mock
	private AuthIssueTokenUseCase authIssueTokenUseCase;

	@Mock
	private AuthLoginAccountFacade authLoginAccountFacade;

	@Mock
	private AuthStoreRefreshTokenUseCase authStoreRefreshTokenUseCase;

	@Mock
	private AuthRefreshTokenFacade authRefreshTokenFacade;

	@Mock
	private AuthLogoutAccountUseCase authLogoutAccountUseCase;

	@InjectMocks
	private AuthFacade authFacade;

	@Test
	@DisplayName("issueAccessToken은 access token 발급을 위임한다")
	void issueAccessTokenDelegates() {
		// given
		String providerId = "provider-id";
		String role = "USER";
		String accessToken = "access-token";
		when(authIssueTokenUseCase.issueToken(providerId, role, true)).thenReturn(accessToken);

		// when
		String result = authFacade.issueAccessToken(providerId, role);

		// then
		assertThat(result).isEqualTo(accessToken);
		verify(authIssueTokenUseCase).issueToken(providerId, role, true);
	}

	@Test
	@DisplayName("login은 계정 조회, 토큰 발급, 리프레시 토큰 저장을 처리한다")
	void loginIssuesTokensAndStoresRefreshToken() {
		// given
		String providerId = "provider-id";
		String email = "user@example.com";
		Provider provider = Provider.GOOGLE;
		AuthRole role = AuthRole.USER;
		String memberPublicId = "member-public-id";
		String accessToken = "access-token";
		String refreshToken = "refresh-token";

		Account account = Account.builder()
			.memberPublicId(memberPublicId)
			.role(role)
			.provider(provider)
			.providerId(providerId)
			.build();

		when(authLoginAccountFacade.loginOrSignup(providerId, email, provider)).thenReturn(account);
		when(authIssueTokenUseCase.issueToken(memberPublicId, role.name(), true)).thenReturn(accessToken);
		when(authIssueTokenUseCase.issueToken(memberPublicId, role.name(), false)).thenReturn(refreshToken);

		// when
		TokenPairDto result = authFacade.login(providerId, email, provider);

		// then
		assertThat(result.accessToken()).isEqualTo(accessToken);
		assertThat(result.refreshToken()).isEqualTo(refreshToken);
		verify(authLoginAccountFacade).loginOrSignup(providerId, email, provider);
		verify(authIssueTokenUseCase).issueToken(memberPublicId, role.name(), true);
		verify(authIssueTokenUseCase).issueToken(memberPublicId, role.name(), false);
		verify(authStoreRefreshTokenUseCase).store(memberPublicId, refreshToken);
	}

	@Test
	@DisplayName("refresh는 refresh facade에 위임한다")
	void refreshDelegates() {
		// given
		String refreshToken = "refresh-token";
		String accessToken = "access-token";
		TokenPairDto expected = new TokenPairDto("new-access", "new-refresh");
		when(authRefreshTokenFacade.refresh(refreshToken, accessToken)).thenReturn(expected);

		// when
		TokenPairDto result = authFacade.refresh(refreshToken, accessToken);

		// then
		assertThat(result).isEqualTo(expected);
		verify(authRefreshTokenFacade).refresh(refreshToken, accessToken);
	}

	@Test
	@DisplayName("logout은 logout use case에 위임한다")
	void logoutDelegates() {
		// given
		String refreshToken = "refresh-token";
		String accessToken = "access-token";

		// when
		authFacade.logout(refreshToken, accessToken);

		// then
		verify(authLogoutAccountUseCase).logout(refreshToken, accessToken);
	}
}
