package com.bugzero.rarego.benchmark;

import static org.assertj.core.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/** ./gradlew :payment-service:settlementBenchmark -Pbenchmark.sizes=100000,300000 */
class SettlementBenchmarkTest {
	private final Path output = Path.of(System.getProperty("benchmark.output"),
		Instant.now().toString().replace(':', '-'));
	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

	public static void main(String[] args) throws Exception {
		new SettlementBenchmarkTest().compare();
	}

	@Test
	void compare() throws Exception {
		Files.createDirectories(output);
		Files.writeString(output.resolve("results.csv"),
			"mode,scenario,payments,sources,repeat,wall_ms,prepare_ms,payout_ms,gc_ms,outbox_rows,"
				+ "wallet_updates,lock_waits,lock_wait_ms\n");
		int[] sizes = Arrays.stream(System.getProperty("benchmark.sizes", "100000,300000").split(","))
			.mapToInt(Integer::parseInt).toArray();
		int repetitions = Integer.getInteger("benchmark.repetitions", 3);
		int warmup = Integer.getInteger("benchmark.warmup", 1000);
		int chunkSize = Integer.getInteger("benchmark.chunkSize", 1000);
		List<String> modes = List.of(System.getProperty("benchmark.modes", "single,partitioned,two-stage").split(","));
		assertThat(Arrays.stream(sizes).allMatch(size -> size > 0 && size <= 1000000)).isTrue();
		assertThat(repetitions).isBetween(1, 10);
		assertThat(chunkSize).isBetween(1, 10000);
		assertThat(modes).allMatch(mode -> List.of("single", "partitioned", "two-stage").contains(mode));
		var environment = new java.util.LinkedHashMap<String, Object>(
			Map.of("java", System.getProperty("java.runtime.version"), "os", System.getProperty("os.name"),
				"processors", Runtime.getRuntime().availableProcessors(), "maxHeap", Runtime.getRuntime().maxMemory(),
				"sizes", sizes, "repetitions", repetitions, "warmupPayments", warmup, "chunkSize", chunkSize,
				"baseline", "36e3b8e9", "scope", "DB job including Outbox persistence; no Kafka publication"));
		environment.put("payoutModel", "chunk-partial-sum-v1");
		environment.put("payoutIsolation", "READ_COMMITTED");
		Files.writeString(output.resolve("environment.json"), json.writerWithDefaultPrettyPrinter()
			.writeValueAsString(environment));
		for (int size : sizes) {
			for (int round = 0; round < repetitions; round++) {
				for (int m = 0; m < modes.size(); m++) {
					runMode(modes.get((m + round) % modes.size()), size, warmup, round + 1);
				}
			}
		}
		System.out.println("BENCHMARK_RESULTS=" + output.toAbsolutePath());
	}

	private void runMode(String mode, int size, int warmup, int round) throws Exception {
		try (ConfigurableApplicationContext context = start(mode)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			Files.writeString(output.resolve("mysql.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
				jdbc.queryForList("""
					SHOW VARIABLES WHERE Variable_name IN ('version','innodb_buffer_pool_size',
					'innodb_flush_log_at_trx_commit','sync_binlog','transaction_isolation')
					""")));
			SettlementBenchmarkFixture fixture = new SettlementBenchmarkFixture(jdbc);
			JobOperatorTestUtils operator = new JobOperatorTestUtils(
				context.getBean(JobOperator.class), context.getBean(JobRepository.class));
			String jobName = mode.equals("two-stage") ? "settlementJob" : "legacySettlementJob";
			operator.setJob(context.getBean(jobName, Job.class));
			if (warmup > 0) {
				run(operator, fixture, jdbc, mode, "warmup", warmup, 0);
			}
			for (String scenario : List.of("uniform", "hot90")) {
				run(operator, fixture, jdbc, mode, scenario, size, round);
			}
		}
	}

	private ConfigurableApplicationContext start(String mode) {
		// DDL create-drop이 개발 DB를 향하지 않도록 외부 접속 URL을 받지 않는다.
		return new SpringApplicationBuilder(BenchmarkApplication.class).web(WebApplicationType.NONE).run(
			"--spring.config.name=settlement-benchmark",
			"--spring.datasource.url=jdbc:mysql://127.0.0.1:"
				+ (Boolean.getBoolean("benchmark.container") ? "3306" : "13316") + "/settlement_benchmark"
				+ "?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&serverTimezone=Asia/Seoul",
			"--spring.datasource.username=root", "--spring.datasource.password=benchmark-local-only",
			"--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
			"--spring.datasource.hikari.maximum-pool-size=20", "--spring.datasource.hikari.minimum-idle=20",
			"--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.jpa.open-in-view=false",
			"--spring.jpa.show-sql=false", "--spring.jpa.properties.hibernate.format_sql=false",
			"--logging.level.root=WARN", "--logging.level.org.hibernate.SQL=OFF",
			"--logging.level.org.hibernate.tool.schema.internal.ExceptionHandlerLoggedImpl=ERROR",
			"--logging.level.org.hibernate.orm.jdbc.bind=OFF", "--spring.batch.job.enabled=false",
			"--batch.thread.size=" + (mode.equals("single") ? 1 : 5),
			"--custom.payment.settlement.payoutThreadSize=5",
			"--custom.payment.settlement.chunkSize=" + Integer.getInteger("benchmark.chunkSize", 1000),
			"--custom.payment.settlement.holdDays=0");
	}

	private void run(JobOperatorTestUtils operator, SettlementBenchmarkFixture fixture, JdbcTemplate jdbc,
		String mode, String scenario, int payments, int round) throws Exception {
		System.out.printf("BENCHMARK_SEED mode=%s scenario=%s payments=%d repeat=%d%n",
			mode, scenario, payments, round);
		fixture.seed(payments, scenario.equals("hot90"));
		long[] before = databaseCounters(jdbc);
		System.out.printf("BENCHMARK_START mode=%s scenario=%s payments=%d repeat=%d%n",
			mode, scenario, payments, round);
		long gcBefore = gcTime();
		long start = System.nanoTime();
		JobExecution execution = operator.startJob();
		long wall = Duration.ofNanos(System.nanoTime() - start).toMillis();
		long gc = gcTime() - gcBefore;
		long[] after = databaseCounters(jdbc);
		List<Map<String, Object>> steps = jdbc.queryForList("""
			SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT,
			TIMESTAMPDIFF(MICROSECOND, START_TIME, END_TIME)/1000 AS WALL_MS, EXIT_MESSAGE
			FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=? ORDER BY STEP_EXECUTION_ID
			""", execution.getId());
		String name = mode + "-" + scenario + "-" + payments + "-" + round;
		Files.writeString(output.resolve(name + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
			Map.of("mode", mode, "scenario", scenario, "payments", payments, "repeat", round,
				"wallMs", wall, "gcMs", gc, "status", execution.getStatus().name(), "steps", steps,
				"dbCountersBefore", before, "dbCountersAfter", after)));
		assertThat(execution.getExitStatus().getExitCode())
			.as(name + ": " + steps).isEqualTo("COMPLETED");
		fixture.verify(payments, mode.equals("two-stage"));
		Files.writeString(output.resolve(name + "-counts.json"), json.writerWithDefaultPrettyPrinter()
			.writeValueAsString(Map.of(
				"payoutRows", fixture.count("SELECT COUNT(*) FROM payment_settlement_payout"),
				"payoutSources", fixture.count("SELECT COALESCE(SUM(source_count),0) FROM payment_settlement_payout"),
				"ledgerRows", fixture.count("SELECT COUNT(*) FROM payment_transaction"),
				"completedSources", fixture.count("SELECT COUNT(*) FROM payment_settlement WHERE status='DONE'"))));
		long prepare = phase(execution, "mainStep");
		long payout = phase(execution, "payoutMainStep");
		String row = String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n", mode, scenario,
			payments, payments * 2, round, wall, prepare, payout, gc,
			fixture.count("SELECT COUNT(*) FROM outbox_event"),
			after[0] - before[0], after[1] - before[1], after[2] - before[2]);
		Files.writeString(output.resolve("results.csv"), row, StandardOpenOption.APPEND);
		System.out.print("BENCHMARK_RESULT " + row);
	}

	private long phase(JobExecution execution, String name) {
		return execution.getStepExecutions().stream().filter(step -> step.getStepName().equals(name))
			.mapToLong(step -> Duration.between(step.getStartTime(), step.getEndTime()).toMillis()).sum();
	}

	private long gcTime() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream()
			.mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
	}

	private long[] databaseCounters(JdbcTemplate jdbc) {
		long updates = jdbc.queryForObject("""
			SELECT COALESCE(SUM(COUNT_UPDATE),0) FROM performance_schema.table_io_waits_summary_by_table
			WHERE OBJECT_SCHEMA='settlement_benchmark' AND OBJECT_NAME='payment_wallet'
			""", Long.class);
		long waits = jdbc.queryForObject("""
			SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'
			""", Long.class);
		long time = jdbc.queryForObject("""
			SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_time'
			""", Long.class);
		return new long[] {updates, waits, time};
	}
}
