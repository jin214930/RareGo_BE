package com.bugzero.rarego.in;

import java.time.LocalDateTime;
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
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.out.SettlementRepository;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SettlementBatchConfig {
	private final PaymentFacade paymentFacade;
	private final SettlementRepository settlementRepository;
	private final SettlementPayoutBatchConfig payoutConfig;
	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final EntityManagerFactory entityManagerFactory;

	@Value("${custom.payment.settlement.chunkSize:10}")
	private int chunkSize;
	@Value("${batch.thread.size:5}")
	private int threadSize;
	@Value("${custom.payment.settlement.payoutThreadSize:5}")
	private int payoutThreadSize;
	@Value("${custom.payment.settlement.holdDays:7}")
	private int settlementHoldDays;

	@Bean
	public Job settlementJob() {
		return new JobBuilder("settlementJob", jobRepository)
			.start(settlementBoundaryStep())
			.next(mainStep())
			.next(payoutConfig.payoutMainStep())
			.build();
	}

	@Bean
	public Step settlementBoundaryStep() {
		return new StepBuilder("settlementBoundaryStep", jobRepository)
			.tasklet((contribution, chunkContext) -> {
				ExecutionContext context = contribution.getStepExecution().getJobExecution().getExecutionContext();
				if (!context.containsKey("settlementCutoff")) {
					LocalDateTime cutoff = LocalDateTime.now().minusDays(settlementHoldDays);
					SettlementRepository.IdBounds bounds = settlementRepository.findReadyBounds(cutoff);
					context.putString("settlementCutoff", cutoff.toString());
					context.putLong("settlementMinId", bounds.getMinId());
					context.putLong("settlementMaxId", bounds.getMaxId());
					context.putInt("settlementGridSize", threadSize);
					context.putInt("payoutGridSize", payoutThreadSize);
				}
				return RepeatStatus.FINISHED;
			}, transactionManager).build();
	}

	@Bean
	public Step mainStep() {
		return new StepBuilder("mainStep", jobRepository)
			.partitioner("settlementPreparationStep", settlementIdPartitioner(null, null, null))
			.step(subStep())
			.gridSize(threadSize)
			.taskExecutor(executor())
			.build();
	}

	@Bean
	@StepScope
	public Partitioner settlementIdPartitioner(
		@Value("#{jobExecutionContext['settlementMinId']}") Long minId,
		@Value("#{jobExecutionContext['settlementMaxId']}") Long maxId,
		@Value("#{jobExecutionContext['settlementGridSize']}") Integer gridSize) {
		return new SettlementIdRangePartitioner(minId, maxId, gridSize);
	}

	@Bean
	public Step subStep() {
		return new StepBuilder("settlementPreparationStep", jobRepository)
			.<Long, Long>chunk(chunkSize)
			.transactionManager(transactionManager)
			.reader(settlementReader(null, null, null))
			.writer(settlementWriter(null))
			.build();
	}

	@Bean
	@StepScope
	public JpaCursorItemReader<Long> settlementReader(
		@Value("#{stepExecutionContext['minId']}") Long minId,
		@Value("#{stepExecutionContext['maxId']}") Long maxId,
		@Value("#{jobExecutionContext['settlementCutoff']}") String cutoff) {
		return new JpaCursorItemReaderBuilder<Long>()
			.name("settlementReader")
			.entityManagerFactory(entityManagerFactory)
			.queryString("""
				SELECT s.id FROM Settlement s
				WHERE s.status = 'READY' AND s.createdAt < :cutoff
				AND s.id BETWEEN :minId AND :maxId ORDER BY s.id
				""")
			.parameterValues(Map.of("minId", minId, "maxId", maxId, "cutoff", LocalDateTime.parse(cutoff)))
			.saveState(false)
			.build();
	}

	@Bean
	@StepScope
	public ItemWriter<Long> settlementWriter(@Value("#{stepExecution.jobExecution.jobInstanceId}") Long runId) {
		return chunk -> paymentFacade.prepareSettlements(runId, chunk.getItems());
	}

	@Bean
	public TaskExecutor executor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(threadSize);
		executor.setMaxPoolSize(threadSize);
		executor.setThreadNamePrefix("settlement-prepare-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		return executor;
	}
}
