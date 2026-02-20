package com.bugzero.rarego.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

public final class AccessTokenBlacklistKeyResolver {
	private static final String PREFIX = "ATB:";

	private AccessTokenBlacklistKeyResolver() {
	}

	public static String key(String accessToken) {
		if (accessToken == null || accessToken.isBlank()) {
			throw new CustomException(ErrorType.AUTH_MEMBER_REQUIRED);
		}
		return PREFIX + sha256(accessToken);
	}

	private static String sha256(String value) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			byte[] digest = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
		}
	}
}
