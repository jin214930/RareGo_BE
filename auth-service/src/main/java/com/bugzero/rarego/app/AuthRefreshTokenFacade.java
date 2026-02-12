package com.bugzero.rarego.app;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.config.JwtProperties;
import com.bugzero.rarego.domain.Account;
import com.bugzero.rarego.domain.RefreshToken;
import com.bugzero.rarego.domain.TokenPairDto;
import com.bugzero.rarego.out.AccountRepository;
import com.bugzero.rarego.out.RefreshTokenRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.global.security.JwtParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthRefreshTokenFacade {
	private final RefreshTokenRepository refreshTokenRepository;
	private final JwtParser jwtParser;
	private final AuthIssueTokenUseCase authIssueTokenUseCase;
	private final AuthAccessTokenBlacklistUseCase authAccessTokenBlacklistUseCase;
	private final AccountRepository accountRepository;
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties jwtProperties;

	@Transactional
	public TokenPairDto refresh(String refreshToken, String accessToken) {

		// 1. refresh token 입력 검증
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new CustomException(ErrorType.AUTH_REFRESH_TOKEN_REQUIRED);
		}

		// 2. JWT 검증
		String memberPublicId = jwtParser.parseRefreshPublicId(refreshToken);
		if (memberPublicId == null) {
			throw new CustomException(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
		}

		// 3. redis 검증
		if (!refreshTokenStore.isValid(refreshToken, memberPublicId)) {
			throw new CustomException(ErrorType.AUTH_REFRESH_TOKEN_INVALID);
		}

		// 4. 계정 확인(탈퇴면 refresh 차단 + revoke)
		Account account = accountRepository.findByMemberPublicId(memberPublicId)
			.orElseThrow(() -> new CustomException(ErrorType.AUTH_REFRESH_TOKEN_INVALID));

		if (account.isDeleted()) {
			refreshTokenStore.revoke(refreshToken);
			throw new CustomException(ErrorType.AUTH_ACCOUNT_DELETED);
		}

		// 5. 회전(rotate): 기존 refresh 폐기
		refreshTokenStore.revoke(refreshToken);

		// 6. 생성
		String newAccessToken = authIssueTokenUseCase.issueToken(account.getMemberPublicId(), account.getRole().name(),
			true);
		String newRefreshToken = authIssueTokenUseCase.issueToken(account.getMemberPublicId(), account.getRole().name(),
			false);
		refreshTokenStore.save(newRefreshToken, account.getMemberPublicId(), jwtProperties.getAccessTokenExpireSeconds());

		// 블랙리스트 추가
		authAccessTokenBlacklistUseCase.blacklist(accessToken);

		return new TokenPairDto(newAccessToken, newRefreshToken);
	}
}
