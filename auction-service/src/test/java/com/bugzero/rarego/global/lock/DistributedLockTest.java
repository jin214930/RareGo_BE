package com.bugzero.rarego.global.lock;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
	"spring.data.redis.host=localhost",
	"spring.data.redis.port=6379"
})
@Import(DistributedLockTest.TestLockService.class)
class DistributedLockTest {

	@Autowired
	private RedissonClient redissonClient; // [변경] RedisTemplate 대신 RedissonClient 주입

	@Autowired
	private TestLockService testLockService;

	/**
	 * 1. Redisson 연결 테스트
	 * - RedissonClient가 Redis에 정상적으로 연결되는지 검증
	 */
	@Test
	@DisplayName("Redisson 연결 테스트: 값을 저장하고 조회할 수 있다.")
	void redisson_connection_test() {
		// Given
		String key = "test:redisson:connection";
		String value = "Hello Redisson";
		RBucket<String> bucket = redissonClient.getBucket(key);

		// When
		bucket.set(value);
		String result = bucket.get();

		// Then
		assertThat(result).isEqualTo(value);

		// Clean up
		bucket.delete();
	}

	/**
	 * 2. 분산 락 동시성 테스트
	 * - 구현체가 Redisson으로 바뀌어도 테스트 로직(100명 동시 요청)은 동일함
	 */
	@Test
	@DisplayName("분산 락 테스트: 동시에 100개의 요청이 들어와도 순차적으로 처리되어야 한다.")
	void distributed_lock_concurrency_test() throws InterruptedException {
		// Given
		int threadCount = 100;
		ExecutorService executorService = Executors.newFixedThreadPool(32);
		CountDownLatch latch = new CountDownLatch(threadCount);

		testLockService.setStock(threadCount); // 재고 100개 설정

		// When
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				try {
					// 락이 걸린 메서드 호출
					testLockService.decreaseStock("item:1");
				} catch (Exception e) {
					System.out.println(e.getMessage());
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await(); // 모든 스레드 종료 대기

		// Then
		int remainStock = testLockService.getStock();
		assertThat(remainStock).isEqualTo(0); // 100번 차감되어 0이어야 함
	}

	@Service
	static class TestLockService {
		private final AtomicInteger stock = new AtomicInteger(0);

		public void setStock(int count) {
			this.stock.set(count);
		}

		public int getStock() {
			return this.stock.get();
		}

		@DistributedLock(key = "'lock:' + #key", waitTime = 10, leaseTime = 5)
		public void decreaseStock(String key) {
			stock.decrementAndGet();
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
}