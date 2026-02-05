package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.out.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthLogoutAccountUseCase {
	private final RefreshTokenRepository refreshTokenRepository;
	private final AuthAccessTokenBlacklistUseCase authAccessTokenBlacklistUseCase;

	@Transactional
	public void logout(String refreshToken, String accessToken) {
		authAccessTokenBlacklistUseCase.blacklist(accessToken);

		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}

		refreshTokenRepository.findByRefreshToken(refreshToken)
			.ifPresent(refreshTokenRepository::delete);
	}
}
