package com.bugzero.rarego.in;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.domain.Settlement;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SettlementBatchConfig {
	private final PaymentFacade paymentFacade;
	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final EntityManagerFactory entityManagerFactory;

	@Value("${custom.payment.settlement.chunkSize:10}")
	private int chunkSize;

	@Value("${batch.thread.size:5}")
	private int threadSize;

	@Value("${custom.payment.settlement.holdDays:7}")
	private int settlementHoldDays;

	@Bean
	public Job settlementJob() {
		return new JobBuilder("settlementJob", jobRepository)
			.start(mainStep())
			.build();
	}

	@Bean
	public Step mainStep() {
		return new StepBuilder("mainStep", jobRepository)
			.partitioner("subStep", recipientIdPartitioner())
			.step(subStep())
			.gridSize(threadSize)
			.taskExecutor(executor())
			.build();
	}

	@Bean
	public Partitioner recipientIdPartitioner() {
		return gridSize -> {
			Map<String, ExecutionContext> map = new HashMap<>();
			for (int i = 0; i < gridSize; i++) {
				ExecutionContext context = new ExecutionContext();
				context.putInt("partitionIndex", i);
				context.putInt("gridSize", gridSize);
				map.put("partition" + i, context);
			}

			return map;
		};
	}

	@Bean
	public Step subStep() {
		return new StepBuilder("settlementProcessStep", jobRepository)
			.<Settlement, Settlement>chunk(chunkSize)
			.transactionManager(transactionManager)
			.reader(settlementReader(null, null))
			.writer(settlementWriter())
			.build();
	}

	@Bean
	@StepScope
	public JpaCursorItemReader<Settlement> settlementReader(
		@Value("#{stepExecutionContext['partitionIndex']}") Integer partitionIndex,
		@Value("#{stepExecutionContext['gridSize']}") Integer gridSize
	) {
		LocalDateTime cutoffDate = LocalDateTime.now().minusDays(settlementHoldDays);

		Map<String, Object> parameters = new HashMap<>();
		parameters.put("cutoffDate", cutoffDate);
		parameters.put("partitionIndex", partitionIndex);
		parameters.put("gridSize", gridSize);

		return new JpaCursorItemReaderBuilder<Settlement>()
			.name("settlementReader")
			.entityManagerFactory(entityManagerFactory)
			.queryString("""
				SELECT s FROM Settlement s
				WHERE s.status = 'READY' AND s.createdAt < :cutoffDate
				AND MOD(s.recipient.id, :gridSize) = :partitionIndex
				ORDER BY s.id ASC
				""")
			.parameterValues(parameters)
			.build();
	}

	@Bean
	@StepScope
	public ItemWriter<Settlement> settlementWriter() {
		return chunk -> paymentFacade.processSettlements(chunk.getItems());
	}

	// 정산 스레드 풀 설정
	@Bean
	public TaskExecutor executor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(threadSize);
		executor.setMaxPoolSize(threadSize);
		executor.setThreadNamePrefix("settlement-thread-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.initialize();
		return executor;
	}
}
