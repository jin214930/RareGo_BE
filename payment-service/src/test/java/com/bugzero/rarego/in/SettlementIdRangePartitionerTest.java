package com.bugzero.rarego.in;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SettlementIdRangePartitionerTest {
	@Test
	void rangeIsCoveredOnceUsingPersistedPartitionCount() {
		var partitions = new SettlementIdRangePartitioner(101, 110, 3).partition(10);
		assertThat(partitions).hasSize(3);
		long next = 101;
		for (var context : partitions.values()) {
			assertThat(context.getLong("minId")).isEqualTo(next);
			next = context.getLong("maxId") + 1;
		}
		assertThat(next).isEqualTo(111);
	}

	@Test
	void smallAndEmptyRangesDoNotCreateOverlappingPartitions() {
		assertThat(new SettlementIdRangePartitioner(100, 101, 5).partition(5)).hasSize(2);
		var empty = new SettlementIdRangePartitioner(1, 0, 5).partition(5);
		assertThat(empty).hasSize(1);
		assertThat(empty.get("partition0").getLong("maxId")).isLessThan(empty.get("partition0").getLong("minId"));
	}
}
