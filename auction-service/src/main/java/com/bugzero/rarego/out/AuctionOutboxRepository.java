package com.bugzero.rarego.out;

import com.bugzero.rarego.domain.AuctionOutbox;
import com.bugzero.rarego.domain.AuctionOutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuctionOutboxRepository extends JpaRepository<AuctionOutbox, Long> {
    // 아직 처리되지 않았고(PENDING), 재시도 횟수가 남은 목록 조회
    List<AuctionOutbox> findAllByStatusAndRetryCountLessThan(
            AuctionOutboxStatus status,
            int maxRetry
    );
}