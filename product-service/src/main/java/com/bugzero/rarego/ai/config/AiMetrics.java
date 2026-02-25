package com.bugzero.rarego.ai.config;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AiMetrics {

	private final MeterRegistry registry;
	private final AtomicInteger externalInFlight = new AtomicInteger();

	@PostConstruct
	void register() {
		Gauge.builder("product.ai.external.inflight", externalInFlight, AtomicInteger::get)
			.description("In-flight external AI requests")
			.register(registry);
	}

	public void incrementExternalRequest() {
		registry.counter("product.ai.external.request.total").increment();
	}

	public void incrementExternalTimeout() {
		registry.counter("product.ai.external.error.total", "error_type", "timeout").increment();
	}

	public void incrementExternalError() {
		registry.counter("product.ai.external.error.total", "error_type", "system").increment();
	}

	public void externalStart() {
		externalInFlight.incrementAndGet();
	}

	public void externalFinish(String result, long nanos) {
		Timer.builder("product.ai.external.request.duration")
			.description("External AI request duration")
			.publishPercentileHistogram()
			.publishPercentiles(0.5, 0.9, 0.95, 0.99)
			.tag("result", result)
			.register(registry)
			.record(nanos, TimeUnit.NANOSECONDS);
		externalInFlight.decrementAndGet();
	}

	public void recordEmbeddingDuration(String result, long nanos) {
		Timer.builder("product.ai.embedding.duration")
			.description("Embedding generation duration")
			.publishPercentileHistogram()
			.publishPercentiles(0.5, 0.9, 0.95, 0.99)
			.tag("result", result)
			.register(registry)
			.record(nanos, TimeUnit.NANOSECONDS);
	}

	public void incrementEmbeddingFailure() {
		registry.counter("product.ai.embedding.error.total").increment();
	}

	public void recordVectorSearchDuration(String result, long nanos) {
		Timer.builder("product.ai.vector_search.duration")
			.description("Vector search duration")
			.publishPercentileHistogram()
			.publishPercentiles(0.5, 0.9, 0.95, 0.99)
			.tag("result", result)
			.register(registry)
			.record(nanos, TimeUnit.NANOSECONDS);
	}

	public void incrementVectorSearchFailure() {
		registry.counter("product.ai.vector_search.error.total").increment();
	}
}
