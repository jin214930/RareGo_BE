package com.bugzero.rarego.in;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

public record SettlementIdRangePartitioner(long minId, long maxId, int partitionCount) implements Partitioner {
	@Override
	public Map<String, ExecutionContext> partition(int gridSize) {
		if (partitionCount < 1) {
			throw new IllegalArgumentException("정산 파티션 개수는 1 이상이어야 합니다.");
		}
		Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
		long count = maxId >= minId ? Math.addExact(maxId - minId, 1) : 0;
		long width = Math.max(1, count / partitionCount + (count % partitionCount == 0 ? 0 : 1));
		long start = minId;
		for (int i = 0; i < partitionCount; i++) {
			long end = count == 0 ? maxId : start + Math.min(width - 1, maxId - start);
			ExecutionContext context = new ExecutionContext();
			context.putLong("minId", start);
			context.putLong("maxId", end);
			partitions.put("partition" + i, context);
			if (end >= maxId) {
				break;
			}
			start = end + 1;
		}
		return partitions;
	}
}
