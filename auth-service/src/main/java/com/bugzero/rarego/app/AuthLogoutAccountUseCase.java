package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthLogoutAccountUseCase {
	private final RefreshTokenStore refreshTokenStore;
	private final AuthAccessTokenBlacklistUseCase authAccessTokenBlacklistUseCase;

	@Transactional
	public void logout(String refreshToken, String accessToken) {
		authAccessTokenBlacklistUseCase.blacklist(accessToken);

		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}

		if (refreshTokenStore.isValid(refreshToken, accessToken)) {
			refreshTokenStore.revoke(refreshToken);
		}
	}
}
