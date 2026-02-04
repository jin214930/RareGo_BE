package com.bugzero.rarego.bounded_context.payment.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bugzero.rarego.bounded_context.payment.domain.PaymentOutbox;
import com.bugzero.rarego.bounded_context.payment.domain.PaymentOutboxStatus;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutbox, Long> {
    List<PaymentOutbox> findTop100ByStatusAndRetryCountLessThanOrderByIdAsc(
            PaymentOutboxStatus status,
            int retryCount);
}

