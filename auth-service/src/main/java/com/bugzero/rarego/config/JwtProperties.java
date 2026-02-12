package com.bugzero.rarego.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

import lombok.Getter;

@Getter
@Component
public class JwtProperties {
	private final int accessTokenExpireSeconds;
	private final int refreshTokenExpireSeconds;

	public JwtProperties(
		@Value("${jwt.access-token-expire-seconds}") int accessTokenExpireSeconds,
		@Value("${jwt.refresh-token-expire-seconds}") int refreshTokenExpireSeconds
	) {
		if (accessTokenExpireSeconds <= 0 || refreshTokenExpireSeconds <= 0) {
			throw new CustomException(ErrorType.JWT_EXPIRE_SECONDS_INVALID);
		}
		this.accessTokenExpireSeconds = accessTokenExpireSeconds;
		this.refreshTokenExpireSeconds = refreshTokenExpireSeconds;
	}

}