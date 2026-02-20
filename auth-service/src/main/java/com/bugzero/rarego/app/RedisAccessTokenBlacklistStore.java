package com.bugzero.rarego.app;

import java.time.Duration;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

import com.bugzero.rarego.global.security.AccessTokenBlacklistKeyResolver;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RedisAccessTokenBlacklistStore implements AccessTokenBlacklistStore {
	private static final String BLACKLIST_MARKER = "1";
	private final RedissonClient redisson;

	@Override
	public void save(String accessToken, Long ttlSeconds) {
		if (accessToken == null || accessToken.isBlank() || ttlSeconds == null || ttlSeconds <= 0) {
			return;
		}
		redisson.<String>getBucket(AccessTokenBlacklistKeyResolver.key(accessToken))
			.set(BLACKLIST_MARKER, Duration.ofSeconds(ttlSeconds));
	}

	@Override
	public boolean exists(String accessToken) {
		if (accessToken == null || accessToken.isBlank()) {
			return false;
		}
		return redisson.getBucket(AccessTokenBlacklistKeyResolver.key(accessToken)).isExists();
	}
}
