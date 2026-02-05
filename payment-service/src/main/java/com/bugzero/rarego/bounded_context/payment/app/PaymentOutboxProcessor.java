package com.bugzero.rarego.bounded_context.payment.app;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.bounded_context.payment.domain.PaymentOutbox;
import com.bugzero.rarego.bounded_context.payment.domain.PaymentOutboxStatus;
import com.bugzero.rarego.bounded_context.payment.domain.PaymentOutboxType;
import com.bugzero.rarego.bounded_context.payment.out.AuctionOrderApiClient;
import com.bugzero.rarego.bounded_context.payment.out.PaymentOutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOutboxProcessor {
    private static final int MAX_RETRY = 5;

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final AuctionOrderApiClient auctionOrderApiClient;

    @Transactional
    public void process(Long outboxId) {
        PaymentOutbox outbox = paymentOutboxRepository.findById(outboxId)
                .orElse(null);
        if (outbox == null || outbox.getStatus() == PaymentOutboxStatus.SENT) {
            return;
        }

        processInternal(outbox);
    }

    @Transactional
    public int retryPending() {
        List<PaymentOutbox> pendings = paymentOutboxRepository
                .findTop100ByStatusAndRetryCountLessThanOrderByIdAsc(PaymentOutboxStatus.PENDING, MAX_RETRY);

        int success = 0;
        for (PaymentOutbox outbox : pendings) {
            if (processInternal(outbox)) {
                success++;
            }
        }
        return success;
    }

    private boolean processInternal(PaymentOutbox outbox) {
        try {
            if (outbox.getType() == PaymentOutboxType.AUCTION_ORDER_FAIL) {
                auctionOrderApiClient.failOrder(outbox.getAuctionId());
            }
            outbox.markSent();
            return true;
        } catch (Exception e) {
            outbox.markFailed(e.getMessage(), MAX_RETRY);
            log.error("아웃박스 처리 실패: id={}, type={}, error={}",
                    outbox.getId(), outbox.getType(), e.getMessage());
            return false;
        }
    }
}

