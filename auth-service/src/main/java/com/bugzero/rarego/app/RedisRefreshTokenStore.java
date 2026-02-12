package com.bugzero.rarego.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

@Repository
public class RedisRefreshTokenStore implements RefreshTokenStore {
	private final RedissonClient redisson;
	public RedisRefreshTokenStore(RedissonClient redisson) { this.redisson = redisson; }

	private String key(String refreshToken) {
		return "RT:" + sha256(refreshToken);
	}

	@Override
	public void save(String refreshToken, String publicId, long ttlSeconds) {
		redisson.<String>getBucket(key(refreshToken)).set(publicId, Duration.ofSeconds(ttlSeconds));
	}

	@Override
	public boolean isValid(String refreshToken, String publicId) {
		String owner = redisson.<String>getBucket(key(refreshToken)).get();
		return owner != null && owner.equals(publicId);
	}

	@Override
	public void revoke(String refreshToken) {
		redisson.getBucket(key(refreshToken)).delete();
	}

	private static String sha256(String s) {
		if (s == null || s.isBlank()) {
			throw new CustomException(ErrorType.AUTH_REFRESH_TOKEN_REQUIRED);
		}
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
		}
	}
}
