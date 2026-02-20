package com.bugzero.rarego.global.security;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisAccessTokenBlacklistChecker {
	private final RedissonClient redisson;

	public boolean isBlacklisted(String accessToken) {
		if (accessToken == null || accessToken.isBlank()) {
			return false;
		}
		return redisson.getBucket(AccessTokenBlacklistKeyResolver.key(accessToken)).isExists();
	}
}
