package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.security.JwtParser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthLogoutAccountUseCase {
	private final RefreshTokenStore refreshTokenStore;
	private final AuthAccessTokenBlacklistUseCase authAccessTokenBlacklistUseCase;
	private final JwtParser jwtParser;

	@Transactional
	public void logout(String refreshToken, String accessToken) {
		authAccessTokenBlacklistUseCase.blacklist(accessToken);
		String publicId = jwtParser.parseRefreshPublicId(refreshToken);

		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}

		if (!publicId.isBlank() && refreshTokenStore.isValid(refreshToken, publicId)) {
			refreshTokenStore.revoke(refreshToken);
		}
	}
}
