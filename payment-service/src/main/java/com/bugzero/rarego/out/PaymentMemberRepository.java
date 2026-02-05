package com.bugzero.rarego.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bugzero.rarego.domain.PaymentMember;

public interface PaymentMemberRepository extends JpaRepository<PaymentMember, Long> {
	Optional<PaymentMember> findByPublicId(String publicId);
}
