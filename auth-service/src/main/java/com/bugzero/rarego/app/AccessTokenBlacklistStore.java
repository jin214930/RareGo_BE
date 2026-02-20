package com.bugzero.rarego.app;

public interface AccessTokenBlacklistStore {
	void save(String accessToken, Long ttlSeconds);
	boolean exists(String accessToken);
}
