package com.bugzero.rarego.global.security;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SystemAuthTokenProvider {
	@Value("${custom.accessToken.expirationSeconds}")
	private int accessTokenExpirationSeconds;

	private JwtProvider jwtProvider;

	public String getSystemAccessToken() {
		return jwtProvider.issueToken(
			accessTokenExpirationSeconds,
			Map.of(
			"publicId", "00000000-0000-0000-0000-000000000001",
			"role", "ADMIN"
			)
		);
	}
}