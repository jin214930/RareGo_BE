package com.bugzero.rarego.app;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.bugzero.rarego.global.security.JwtParser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthAccessTokenBlacklistUseCase {
	private final AccessTokenBlacklistStore accessTokenBlacklistStore;
	private final JwtParser jwtParser;

	public void blacklist(String accessToken) {
		if (accessToken == null || accessToken.isBlank()) {
			return;
		}

		// 이미 만료됐다면 블랙리스트 스킵
		LocalDateTime expiresAt = jwtParser.expiresAt(accessToken);
		LocalDateTime now = LocalDateTime.now();
		if (expiresAt == null || !expiresAt.isAfter(now)) {
			return;
		}

		// 이미 블랙리스트에 존재하면 스킵
		if (accessTokenBlacklistStore.exists(accessToken)) {
			return;
		}

		Long ttlSeconds = Duration.between(now, expiresAt).getSeconds();
		if (ttlSeconds <= 0) {
			return;
		}

		accessTokenBlacklistStore.save(accessToken, ttlSeconds);
	}

	// 블랙리스트 처리 됐는지 확인
	public boolean isBlacklisted(String accessToken) {
		if (accessToken == null || accessToken.isBlank()) {
			return false;
		}
		return accessTokenBlacklistStore.exists(accessToken);
	}
}
