package com.bugzero.rarego.bounded_context.app;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.app.AuthAccessTokenBlacklistUseCase;
import com.bugzero.rarego.app.AuthLogoutAccountUseCase;
import com.bugzero.rarego.app.RefreshTokenStore;

@ExtendWith(MockitoExtension.class)
class AuthLogoutAccountUseCaseTest {
	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private AuthAccessTokenBlacklistUseCase authAccessTokenBlacklistUseCase;

	@InjectMocks
	private AuthLogoutAccountUseCase authLogoutAccountUseCase;

	@Test
	@DisplayName("refresh 토큰이 null이면 access token만 블랙리스트 처리한다.")
	void logoutBlacklistsWhenRefreshTokenNull() {
		// given
		String accessToken = "access-token";

		// when
		authLogoutAccountUseCase.logout(null, accessToken);

		// then
		verify(authAccessTokenBlacklistUseCase).blacklist(accessToken);
		verifyNoInteractions(refreshTokenStore);
	}

	@Test
	@DisplayName("refresh 토큰이 공백이면 access token만 블랙리스트 처리한다.")
	void logoutBlacklistsWhenRefreshTokenBlank() {
		// given
		String accessToken = "access-token";

		// when
		authLogoutAccountUseCase.logout(" ", accessToken);

		// then
		verify(authAccessTokenBlacklistUseCase).blacklist(accessToken);
		verifyNoInteractions(refreshTokenStore);
	}

	@Test
	@DisplayName("refresh 토큰이 유효하면 저장소에서 폐기한다.")
	void logoutRevokesRefreshTokenWhenValid() {
		// given
		String accessToken = "access-token";
		String refreshTokenValue = "refresh-token";
		when(refreshTokenStore.isValid(refreshTokenValue, accessToken)).thenReturn(true);

		// when
		authLogoutAccountUseCase.logout(refreshTokenValue, accessToken);

		// then
		verify(authAccessTokenBlacklistUseCase).blacklist(accessToken);
		verify(refreshTokenStore).isValid(refreshTokenValue, accessToken);
		verify(refreshTokenStore).revoke(refreshTokenValue);
	}

	@Test
	@DisplayName("refresh 토큰이 유효하지 않으면 폐기하지 않는다.")
	void logoutSkipsRevokeWhenRefreshTokenInvalid() {
		// given
		String accessToken = "access-token";
		String refreshTokenValue = "refresh-token";
		when(refreshTokenStore.isValid(refreshTokenValue, accessToken)).thenReturn(false);

		// when
		authLogoutAccountUseCase.logout(refreshTokenValue, accessToken);

		// then
		verify(authAccessTokenBlacklistUseCase).blacklist(accessToken);
		verify(refreshTokenStore).isValid(refreshTokenValue, accessToken);
		verify(refreshTokenStore, never()).revoke(anyString());
	}
}
