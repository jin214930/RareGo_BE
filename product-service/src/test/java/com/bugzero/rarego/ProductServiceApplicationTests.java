package com.bugzero.rarego;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.bugzero.rarego.ai.config.TestAiConfig;

@SpringBootTest
@Import(TestAiConfig.class) // 테스트용 가짜 빈 설정을 주입!
class ProductServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
