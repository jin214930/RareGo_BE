package com.bugzero.rarego.global.inbox.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bugzero.rarego.global.inbox.domain.InboxEvent;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {
	/**
	 * 특정 메시지 ID와 컨슈머 그룹의 조합이 이미 존재하는지 확인합니다.
     * @param messageId "AggregateType-ID" 형식의 식별자
     * @param consumerGroup 리스너의 그룹 ID
     * @return 존재 여부 (true: 이미 처리됨, false: 처음 유입됨)
     */
	boolean existsByMessageIdAndConsumerGroup(String messageId, String consumerGroup);

	/**
	 * 특정 시간 이전에 생성된 인박스 이벤트를 삭제합니다.
	 */
	@Modifying
	@Query(value = "DELETE FROM inbox_event WHERE created_at < :threshold LIMIT :batchSize", nativeQuery = true)
	int deleteTopByCreatedAtBefore(@Param("threshold") LocalDateTime threshold, @Param("batchSize") int batchSize);
}
