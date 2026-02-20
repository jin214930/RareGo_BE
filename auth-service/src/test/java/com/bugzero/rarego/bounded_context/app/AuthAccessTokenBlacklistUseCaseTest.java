package com.bugzero.rarego.bounded_context.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.app.AccessTokenBlacklistStore;
import com.bugzero.rarego.app.AuthAccessTokenBlacklistUseCase;
import com.bugzero.rarego.global.security.JwtParser;

@ExtendWith(MockitoExtension.class)
class AuthAccessTokenBlacklistUseCaseTest {
	@Mock
	private AccessTokenBlacklistStore accessTokenBlacklistStore;

	@Mock
	private JwtParser jwtParser;

	@InjectMocks
	private AuthAccessTokenBlacklistUseCase authAccessTokenBlacklistUseCase;

	@Test
	@DisplayName("access token이 없으면 블랙리스트 처리를 건너뛴다.")
	void blacklistSkipsWhenTokenMissing() {
		// given
		String accessToken = " ";

		// when
		authAccessTokenBlacklistUseCase.blacklist(accessToken);

		// then
		verifyNoInteractions(jwtParser, accessTokenBlacklistStore);
	}

	@Test
	@DisplayName("이미 만료된 access token이면 블랙리스트 처리를 건너뛴다.")
	void blacklistSkipsWhenTokenExpired() {
		// given
		String accessToken = "access-token";
		when(jwtParser.expiresAt(accessToken)).thenReturn(LocalDateTime.now().minusMinutes(1));

		// when
		authAccessTokenBlacklistUseCase.blacklist(accessToken);

		// then
		verifyNoInteractions(accessTokenBlacklistStore);
	}

	@Test
	@DisplayName("이미 블랙리스트에 있으면 저장하지 않는다.")
	void blacklistSkipsWhenAlreadyBlacklisted() {
		// given
		String accessToken = "access-token";
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
		when(jwtParser.expiresAt(accessToken)).thenReturn(expiresAt);
		when(accessTokenBlacklistStore.exists(accessToken)).thenReturn(true);

		// when
		authAccessTokenBlacklistUseCase.blacklist(accessToken);

		// then
		verify(accessTokenBlacklistStore).exists(accessToken);
		verify(accessTokenBlacklistStore, never()).save(anyString(), anyLong());
	}

	@Test
	@DisplayName("유효한 access token이면 블랙리스트에 저장한다.")
	void blacklistStoresWhenTokenValid() {
		// given
		String accessToken = "access-token";
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
		when(jwtParser.expiresAt(accessToken)).thenReturn(expiresAt);
		when(accessTokenBlacklistStore.exists(accessToken)).thenReturn(false);

		// when
		authAccessTokenBlacklistUseCase.blacklist(accessToken);

		// then
		ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
		verify(accessTokenBlacklistStore).save(eq(accessToken), ttlCaptor.capture());
		assertThat(ttlCaptor.getValue()).isPositive();
	}

	@Test
	@DisplayName("블랙리스트 조회는 저장소 결과를 반환한다.")
	void isBlacklistedReturnsStoreResult() {
		// given
		String accessToken = "access-token";
		when(accessTokenBlacklistStore.exists(accessToken)).thenReturn(true);

		// when
		boolean result = authAccessTokenBlacklistUseCase.isBlacklisted(accessToken);

		// then
		assertThat(result).isTrue();
		verify(accessTokenBlacklistStore).exists(accessToken);
	}
}
