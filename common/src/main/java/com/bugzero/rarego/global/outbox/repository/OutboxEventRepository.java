package com.bugzero.rarego.global.outbox.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bugzero.rarego.global.outbox.domain.OutboxEvent;
import com.bugzero.rarego.global.outbox.domain.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	@Query("SELECT o FROM OutboxEvent o WHERE o.status = :status ORDER BY o.createdAt ASC")
	List<OutboxEvent> findByStatusOrderByCreatedAt(
		@Param("status") OutboxStatus status,
		Pageable pageable
	);

	@Modifying
	@Query(value = "DELETE FROM outbox_event WHERE status = 'SENT' AND sent_date < :threshold LIMIT :batchSize", nativeQuery = true)
	int deleteSentEventsBatch(@Param("threshold") LocalDateTime threshold, @Param("batchSize") int batchSize);
}
