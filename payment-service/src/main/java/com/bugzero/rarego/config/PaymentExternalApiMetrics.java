package com.bugzero.rarego.config;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentExternalApiMetrics {

	private static final String PROVIDER = "toss";

	private final MeterRegistry registry;
	private final AtomicInteger inFlight = new AtomicInteger();

	@PostConstruct
	void registerGauges() {
		Gauge.builder("payment.pg.inflight", inFlight, AtomicInteger::get)
			.description("In-flight external PG API requests")
			.tag("provider", PROVIDER)
			.register(registry);
	}

	public <T> T record(String apiName, Supplier<T> action) {
		long startNanos = System.nanoTime();
		inFlight.incrementAndGet();

		String result = "success";
		try {
			return action.get();
		} catch (RuntimeException ex) {
			if (isExpectedNotFound(ex)) {
				result = "not_found";
			} else {
				result = "fail";
				incrementError(apiName, classifyError(ex));
			}
			throw ex;
		} finally {
			incrementRequest(apiName, result);
			recordLatency(apiName, result, System.nanoTime() - startNanos);
			inFlight.decrementAndGet();
		}
	}

	public void recordVoid(String apiName, Runnable action) {
		record(apiName, () -> {
			action.run();
			return null;
		});
	}

	private void incrementRequest(String apiName, String result) {
		registry.counter("payment.pg.request.total",
			"provider", PROVIDER,
			"api", apiName,
			"result", result).increment();
	}

	private void incrementError(String apiName, String errorType) {
		registry.counter("payment.pg.error.total",
			"provider", PROVIDER,
			"api", apiName,
			"error_type", errorType).increment();
	}

	private void recordLatency(String apiName, String result, long nanos) {
		Timer.builder("payment.pg.request.duration")
			.description("External PG API request latency")
			.publishPercentileHistogram()
			.publishPercentiles(0.5, 0.9, 0.95, 0.99)
			.tag("provider", PROVIDER)
			.tag("api", apiName)
			.tag("result", result)
			.register(registry)
			.record(nanos, TimeUnit.NANOSECONDS);
	}

	private String classifyError(Throwable ex) {
		if (ex instanceof ResourceAccessException resourceEx) {
			if (hasTimeoutCause(resourceEx)) {
				return "timeout";
			}
			return "network";
		}
		return "application";
	}

	private boolean isExpectedNotFound(Throwable ex) {
		return ex instanceof CustomException customException
			&& customException.getErrorType() == ErrorType.PAYMENT_NOT_FOUND_IN_TOSS;
	}

	private boolean hasTimeoutCause(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			if (current instanceof SocketTimeoutException || current instanceof java.util.concurrent.TimeoutException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
