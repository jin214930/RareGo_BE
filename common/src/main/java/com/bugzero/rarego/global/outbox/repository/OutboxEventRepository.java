package com.bugzero.rarego.global.outbox.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bugzero.rarego.global.outbox.domain.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
