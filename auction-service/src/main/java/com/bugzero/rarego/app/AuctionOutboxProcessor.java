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
     * 스케줄러가 주기적으로(1분마다) 호출
     * 조건: PENDING 상태 + 재시도 횟수 < 3
     *
     * @return 성공적으로 처리된 아웃박스 개수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int retryPending() {
        // 미처리 아웃박스 조회 (FOR UPDATE SKIP LOCKED 적용)
        // → 다른 트랜잭션에서 이미 처리 중인 행은 스킵
        List<AuctionOutbox> pendings = outboxRepository
                .findAllByStatusAndRetryCountLessThanWithLock(
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
            // 1. 페이로드 검증
            Map<String, Object> payload = validatePayload(outbox);

            // 2. DB 상태 먼저 업데이트 (중복 발행 방지)
            outbox.markSent();
            outboxRepository.save(outbox);

            // 3. 이벤트 발행
            publishAuctionEndedEvent(payload);

            log.info(
                    "아웃박스 처리 성공: id={}, auctionId={}, type={}",
                    outboxId, outbox.getAuctionId(), outbox.getType()
            );

        } catch (CustomException e) {
            // 복구 불가능한 예외 (페이로드 오류)
            log.error("아웃박스 검증 실패 (복구 불가): id={}, errorType={}", outboxId, e.getErrorType());
            outbox.markFailedPermanently(e.getErrorType().name(), MAX_RETRY);
            outboxRepository.save(outbox);

        } catch (Exception e) {
            // 복구 가능한 예외 (네트워크 등)
            log.warn("아웃박스 처리 실패 (재시도 가능): id={}, error={}", outboxId, e.getMessage());
            outbox.markFailedTransient(e.getMessage(), MAX_RETRY);
            outboxRepository.save(outbox);

            if (outbox.getStatus() == AuctionOutboxStatus.FAILED) {
                log.warn("아웃박스 최대 재시도 횟수 도달: id={}, lastError={}", outboxId, outbox.getLastError());
            }
        }
    }

    private Map<String, Object> validatePayload(AuctionOutbox outbox) {
        Map<String, Object> payload = outbox.getPayloadAsMap();

        if (payload == null) {
            throw new CustomException(ErrorType.INVALID_OUTBOX_PAYLOAD);
        }

        // 필수 필드 검증
        if (!payload.containsKey("bidderId") || payload.get("bidderId") == null) {
            throw new CustomException(ErrorType.INVALID_OUTBOX_PAYLOAD);
        }
        if (!payload.containsKey("bidAmount") || payload.get("bidAmount") == null) {
            throw new CustomException(ErrorType.INVALID_OUTBOX_PAYLOAD);
        }
        if (!payload.containsKey("productId") || payload.get("productId") == null) {
            throw new CustomException(ErrorType.INVALID_OUTBOX_PAYLOAD);
        }

        // 타입 검증
        if (!(payload.get("bidderId") instanceof Number)) {
            throw new CustomException(ErrorType.INVALID_OUTBOX_PAYLOAD);
        }
        if (!(payload.get("bidAmount") instanceof Number)) {
            throw new CustomException(ErrorType.INVALID_OUTBOX_PAYLOAD);
        }
        if (!(payload.get("productId") instanceof Number)) {
            throw new CustomException(ErrorType.INVALID_OUTBOX_PAYLOAD);
        }

        return payload;
    }

    /**
     * 검증된 payload에서 데이터 추출하여 이벤트 발행
     * 추가 DB 조회 불필요
     *
     * @param payload 검증된 페이로드 맵
     */
    private void publishAuctionEndedEvent(Map<String, Object> payload) {
        Long auctionId = (Long) payload.get("auctionId");
        Long bidderId = ((Number) payload.get("bidderId")).longValue();
        Integer bidAmount = ((Number) payload.get("bidAmount")).intValue();
        Long productId = ((Number) payload.get("productId")).longValue();

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