package com.bugzero.rarego.in;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.batch.core.configuration.annotation.StepScope;
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

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SettlementPayoutBatchConfig {
	private final PaymentFacade paymentFacade;
	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final EntityManagerFactory entityManagerFactory;

	@Value("${custom.payment.settlement.payoutThreadSize:5}")
	private int threadSize;

	@Bean
	public Step payoutMainStep() {
		return new StepBuilder("payoutMainStep", jobRepository)
			.partitioner("settlementPayoutStep", recipientIdPartitioner(null))
			.step(settlementPayoutStep())
			.gridSize(threadSize)
			.taskExecutor(payoutExecutor())
			.build();
	}

	@Bean
	@StepScope
	public Partitioner recipientIdPartitioner(@Value("#{jobExecutionContext['payoutGridSize']}") Integer gridSize) {
		return ignored -> {
			Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
			for (int i = 0; i < gridSize; i++) {
				ExecutionContext context = new ExecutionContext();
				context.putInt("partitionIndex", i);
				context.putInt("gridSize", gridSize);
				partitions.put("partition" + i, context);
			}
			return partitions;
		};
	}

	@Bean
	public Step settlementPayoutStep() {
		return new StepBuilder("settlementPayoutStep", jobRepository)
			.<Long, Long>chunk(1)
			.transactionManager(transactionManager)
			.reader(payoutRecipientReader(null, null, null))
			.writer(payoutWriter(null))
			.build();
	}

	@Bean
	@StepScope
	public JpaCursorItemReader<Long> payoutRecipientReader(
		@Value("#{stepExecution.jobExecution.jobInstanceId}") Long runId,
		@Value("#{stepExecutionContext['partitionIndex']}") Integer partitionIndex,
		@Value("#{stepExecutionContext['gridSize']}") Integer gridSize) {
		return new JpaCursorItemReaderBuilder<Long>()
			.name("payoutRecipientReader")
			.entityManagerFactory(entityManagerFactory)
			.queryString("""
				SELECT p.recipientId FROM SettlementPayout p
				WHERE p.runId = :runId AND p.paid = false
				AND MOD(p.recipientId, :gridSize) = :partitionIndex
				GROUP BY p.recipientId ORDER BY p.recipientId
				""")
			.parameterValues(Map.of("runId", runId, "partitionIndex", partitionIndex, "gridSize", gridSize))
			.saveState(false)
			.build();
	}

	@Bean
	@StepScope
	public ItemWriter<Long> payoutWriter(@Value("#{stepExecution.jobExecution.jobInstanceId}") Long runId) {
		return chunk -> {
			for (Long recipientId : chunk) {
				paymentFacade.depositSettlements(runId, recipientId);
			}
		};
	}

	@Bean
	public TaskExecutor payoutExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(threadSize);
		executor.setMaxPoolSize(threadSize);
		executor.setThreadNamePrefix("settlement-payout-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		return executor;
	}
}
