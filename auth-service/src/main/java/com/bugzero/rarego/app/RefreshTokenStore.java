package com.bugzero.rarego.app;

public interface RefreshTokenStore {
	void save(String refreshToken, String publicId, Long ttlSeconds);
	boolean isValid(String refreshToken, String publicId);
	void revoke(String refreshToken);
	void revokeAllByPublicId(String publicId);
}
