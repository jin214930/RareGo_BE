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
		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}

		String publicId = jwtParser.parseRefreshPublicId(refreshToken);
		if (publicId == null || publicId.isBlank()) {
			return;
		}

		if (refreshTokenStore.isValid(refreshToken, publicId)) {
			refreshTokenStore.revoke(refreshToken);
		}
	}
}
