package com.bugzero.rarego.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.bugzero.rarego.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
	@EntityGraph(attributePaths = {"member"})
	Optional<Payment> findByOrderId(String orderId);
}
