package com.bugzero.rarego.bounded_context.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.app.AuthStoreRefreshTokenUseCase;
import com.bugzero.rarego.app.RefreshTokenStore;
import com.bugzero.rarego.config.JwtProperties;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

@ExtendWith(MockitoExtension.class)
class AuthStoreRefreshTokenUseCaseTest {
	@Mock
	private RefreshTokenStore refreshTokenStore;

	@Mock
	private JwtProperties jwtProperties;

	@InjectMocks
	private AuthStoreRefreshTokenUseCase authStoreRefreshTokenUseCase;

	@Test
	@DisplayName("refresh 토큰 저장 시 만료시간을 포함해 저장한다.")
	void storeSavesRefreshTokenWithExpiry() {
		// given
		String memberPublicId = "550e8400-e29b-41d4-a716-446655440000";
		String refreshToken = "refresh-token";
		when(jwtProperties.getRefreshTokenExpireSeconds()).thenReturn(3600);

		// when
		authStoreRefreshTokenUseCase.store(memberPublicId, refreshToken);

		// then
		verify(jwtProperties).getRefreshTokenExpireSeconds();
		verify(refreshTokenStore).save(refreshToken, memberPublicId, 3600);
	}

	@Test
	@DisplayName("memberPublicId가 없으면 AUTH_MEMBER_REQUIRED 예외가 발생한다.")
	void storeFailsWhenMemberPublicIdMissing() {
		// when
		Throwable thrown = catchThrowable(() -> authStoreRefreshTokenUseCase.store(" ", "refresh-token"));

		// then
		assertThat(thrown)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_MEMBER_REQUIRED);
		verifyNoInteractions(refreshTokenStore, jwtProperties);
	}

	@Test
	@DisplayName("refresh 토큰이 없으면 AUTH_MEMBER_REQUIRED 예외가 발생한다.")
	void storeFailsWhenRefreshTokenMissing() {
		// when
		Throwable thrown = catchThrowable(() -> authStoreRefreshTokenUseCase.store("member-public-id", " "));

		// then
		assertThat(thrown)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUTH_MEMBER_REQUIRED);
		verifyNoInteractions(refreshTokenStore, jwtProperties);
	}
}
