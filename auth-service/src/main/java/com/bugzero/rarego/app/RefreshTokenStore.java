package com.bugzero.rarego.app;

public interface RefreshTokenStore {
	void save(String refreshToken, String publicId, long ttlSeconds);
	boolean isValid(String refreshToken, String publicId);
	void revoke(String refreshToken);
}
