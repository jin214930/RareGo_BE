package com.bugzero.rarego.out;

import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.shared.member.domain.MemberJoinResponseDto;
import com.bugzero.rarego.shared.member.out.MemberApiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberJoinResilienceClient {
	private final MemberApiClient memberApiClient;

	@Retryable(
		retryFor = {ResourceAccessException.class, RestClientException.class},
		maxAttempts = 3,
		backoff = @Backoff(delay = 200)
	)
	public MemberJoinResponseDto join(String email, String memberPublicId) {
		try {
			return memberApiClient.join(email, memberPublicId);
		} catch (RestClientException e) {
			logRetry(e);
			throw e;
		} catch (CustomException e) {
			if (e.getErrorType().getHttpStatus() >= 500) {
				logRetry(e);
				throw new RestClientException(e.getMessage(), e);
			}
			throw e;
		}
	}

	private void logRetry(Throwable e) {
		RetryContext context = RetrySynchronizationManager.getContext();
		int retryCount = context == null ? 0 : context.getRetryCount();
		log.warn("[auth] member join 재시도 - attempt={}, error={}", retryCount + 1, e.getMessage());
	}
}
