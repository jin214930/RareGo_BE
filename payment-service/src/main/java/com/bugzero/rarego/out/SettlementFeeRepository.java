package com.bugzero.rarego.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.bugzero.rarego.domain.SettlementFee;

import jakarta.persistence.LockModeType;

public interface SettlementFeeRepository extends JpaRepository<SettlementFee, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<SettlementFee> findTop1000ByOrderByIdAsc();
}
