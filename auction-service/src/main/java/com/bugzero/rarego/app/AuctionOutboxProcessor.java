package com.bugzero.rarego.app;

import com.bugzero.rarego.domain.AuctionOutbox;
import com.bugzero.rarego.domain.AuctionOutboxStatus;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.AuctionOutboxRepository;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionOutboxProcessor {

    private final AuctionOutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final int MAX_RETRY = 3;

    /**
     * 미처리된 아웃박스들을 배치로 재시도
     *
     * <p>
     * 스케줄러가 주기적으로(1분마다) 호출
     * 조건: PENDING 상태 + 재시도 횟수 < 3
     * </p>
     *
     * @return 성공적으로 처리된 아웃박스 개수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int retryPending() {
        // 미처리 아웃박스 조회
        List<AuctionOutbox> pendings = outboxRepository.findAllByStatusAndRetryCountLessThan(
                AuctionOutboxStatus.PENDING, MAX_RETRY
        );

        log.debug("미처리 아웃박스 조회: count={}", pendings.size());

        // 각 아웃박스마다 처리 시도
        int successCount = 0;
        for (AuctionOutbox outbox : pendings) {
            try {
                this.process(outbox.getId());
                successCount++;

            } catch (Exception e) {
                log.error(
                        "아웃박스 개별 재시도 실패: id={}, error={}",
                        outbox.getId(), e.getMessage()
                );
            }
        }

        log.debug("아웃박스 배치 재시도 완료: total={}, success={}", pendings.size(), successCount);
        return successCount;
    }

    /**
     * 단일 아웃박스 처리
     * <p>
     * 처리 흐름:
     * 1) 아웃박스 조회
     * 2) 이미 처리됨(SENT) 여부 확인 (중복 처리 방지)
     * 3) AuctionEndedEvent 발행
     * 4) 상태 업데이트 (PENDING → SENT 또는 FAILED)
     *
     * @param outboxId 아웃박스 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long outboxId) {
        AuctionOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow(() -> new CustomException(ErrorType.AUCTION_OUTBOX_NOT_FOUND));

        log.debug(
                "아웃박스 처리 시작: id={}, auctionId={}, type={}, status={}",
                outboxId, outbox.getAuctionId(), outbox.getType(), outbox.getStatus()
        );

        if (outbox.getStatus() == AuctionOutboxStatus.SENT) {
            log.debug("아웃박스 이미 처리됨 (중복 처리 방지): id={}", outboxId);
            return;
        }

        try {
            publishAuctionEndedEvent(outbox);

            // 성공 시 상태 업데이트
            outbox.markSent();
            outboxRepository.save(outbox);

            log.info(
                    "아웃박스 처리 성공: id={}, auctionId={}, type={}",
                    outboxId, outbox.getAuctionId(), outbox.getType()
            );

        } catch (Exception e) {
            // 실패 시 재시도 카운트 증가
            log.error(
                    "아웃박스 처리 실패: id={}, auctionId={}, type={}, error={}",
                    outboxId, outbox.getAuctionId(), outbox.getType(), e.getMessage(), e
            );

            outbox.markFailed(e.getMessage(), MAX_RETRY);
            outboxRepository.save(outbox);

            if (outbox.getStatus() == AuctionOutboxStatus.FAILED) {
                log.warn(
                        "아웃박스 최대 재시도 횟수 도달: id={}, auctionId={}, " +
                                "type={}, lastError={}",
                        outboxId, outbox.getAuctionId(), outbox.getType(),
                        outbox.getLastError()
                );
            }
        }
    }

    /**
     * AuctionEndedEvent 발행
     *
     * <p>
     * JSON payload에서 데이터 추출하여 이벤트 발행
     * 추가 DB 조회 불필요
     * </p>
     */
    private void publishAuctionEndedEvent(AuctionOutbox outbox) {
        Map<String, Object> payload = outbox.getPayloadAsMap();

        Long auctionId = outbox.getAuctionId();
        Long bidderId = ((Number) payload.get("bidderId")).longValue();
        Integer bidAmount = ((Number) payload.get("bidAmount")).intValue();
        Long productId = ((Number) payload.get("productId")).longValue();

        // 이벤트 발행
        eventPublisher.publishEvent(new AuctionEndedEvent(
                auctionId,
                bidderId,
                bidAmount,
                productId
        ));

        log.debug(
                "경매 종료 이벤트 발행: auctionId={}, bidderId={}, bidAmount={}, productId={}",
                auctionId, bidderId, bidAmount, productId
        );
    }
}