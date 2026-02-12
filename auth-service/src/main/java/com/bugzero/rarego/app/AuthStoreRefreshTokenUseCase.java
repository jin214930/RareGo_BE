package com.bugzero.rarego.app;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.config.JwtProperties;
import com.bugzero.rarego.domain.RefreshToken;
import com.bugzero.rarego.out.RefreshTokenRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthStoreRefreshTokenUseCase {
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties jwtProperties;

	public void store(String memberPublicId, String refreshToken) {
		if (memberPublicId == null || memberPublicId.isBlank()) {
			throw new CustomException(ErrorType.AUTH_MEMBER_REQUIRED);
		}
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new CustomException(ErrorType.AUTH_MEMBER_REQUIRED);
		}
		refreshTokenStore.save(refreshToken, memberPublicId, jwtProperties.getRefreshTokenExpireSeconds());
	}
}
