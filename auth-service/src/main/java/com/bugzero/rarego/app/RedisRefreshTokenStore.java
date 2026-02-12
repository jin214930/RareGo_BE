package com.bugzero.rarego.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RSetCache;
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

	private String indexKey(String publicId) {
		return "RTI:" + publicId;
	}

	@Override
	public void save(String refreshToken, String publicId, long ttlSeconds) {
		if (publicId == null || publicId.isBlank()) {
			throw new CustomException(ErrorType.AUTH_MEMBER_REQUIRED);
		}

		String tokenKey = key(refreshToken);
		redisson.<String>getBucket(tokenKey).set(publicId, Duration.ofSeconds(ttlSeconds));
		redisson.<String>getSetCache(indexKey(publicId)).add(tokenKey, ttlSeconds, TimeUnit.SECONDS);
	}

	@Override
	public boolean isValid(String refreshToken, String publicId) {
		String owner = redisson.<String>getBucket(key(refreshToken)).get();
		return owner != null && owner.equals(publicId);
	}

	@Override
	public void revoke(String refreshToken) {
		String tokenKey = key(refreshToken);
		String owner = redisson.<String>getBucket(tokenKey).get();
		redisson.getBucket(tokenKey).delete();

		if (owner != null && !owner.isBlank()) {
			redisson.<String>getSetCache(indexKey(owner)).remove(tokenKey);
		}
	}

	@Override
	public void revokeAllByPublicId(String publicId) {
		if (publicId == null || publicId.isBlank()) {
			throw new CustomException(ErrorType.AUTH_MEMBER_REQUIRED);
		}

		RSetCache<String> tokenIndex = redisson.getSetCache(indexKey(publicId));
		Set<String> tokenKeys = tokenIndex.readAll();
		if (!tokenKeys.isEmpty()) {
			redisson.getKeys().delete(tokenKeys.toArray(new String[0]));
		}
		tokenIndex.delete();
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
