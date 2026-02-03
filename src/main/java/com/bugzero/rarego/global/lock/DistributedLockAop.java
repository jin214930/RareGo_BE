package com.bugzero.rarego.global.lock;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLockAop {

	private static final String REDISSON_LOCK_PREFIX = "LOCK:";
	private final RedissonClient redissonClient;
	private final AopForTransaction aopForTransaction;

	@Around("@annotation(com.bugzero.rarego.global.lock.DistributedLock)")
	public Object lock(final ProceedingJoinPoint joinPoint) throws Throwable {
		MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		Method method = signature.getMethod();
		DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);

		// 1. 락 이름 생성 (SpEL 파싱)
		String key = REDISSON_LOCK_PREFIX + CustomSpringELParser.getDynamicValue(
			signature.getParameterNames(), joinPoint.getArgs(), distributedLock.key());

		// 2. 락 객체 가져오기 (RLock)
		RLock rLock = redissonClient.getLock(key);

		try {
			// 3. 락 획득 시도
			// waitTime만큼 기다리고, 획득하면 leaseTime만큼 점유
			boolean available = rLock.tryLock(
				distributedLock.waitTime(),
				distributedLock.leaseTime(),
				distributedLock.timeUnit()
			);

			if (!available) {
				log.warn("락 획득 실패 - Key: {}", key);
				// 503 Service Unavailable 에러 발생
				throw new CustomException(ErrorType.LOCK_ACQUISITION_FAILED);
			}

			// 4. 트랜잭션 분리 실행 (커밋 후 락 해제를 위해)
			return aopForTransaction.proceed(joinPoint);

		} catch (InterruptedException e) {
			throw new InterruptedException();
		} finally {
			// 5. 락 해제 (현재 스레드가 락을 가지고 있을 때만 해제)
			try {
				if (rLock.isHeldByCurrentThread()) {
					rLock.unlock();
				}
			} catch (IllegalMonitorStateException e) {
				log.warn("락 해제 중 이미 만료되었거나 해제됨 - Key: {}", key);
			}
		}
	}
}