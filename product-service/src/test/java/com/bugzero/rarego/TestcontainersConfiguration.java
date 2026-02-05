package com.bugzero.rarego;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
	@Bean
	@ServiceConnection // 핵심! 스프링이 알아서 컨테이너 정보를 주입해줍니다.
	public ElasticsearchContainer elasticsearchContainer() {
		return new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.10.2")
			.withEnv("discovery.type", "single-node")
			.withEnv("xpack.security.enabled", "false");
	}
}
