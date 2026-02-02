package com.bugzero.rarego.boundedContext.auction.app;

import com.bugzero.rarego.boundedContext.auction.domain.Auction;
import com.bugzero.rarego.boundedContext.auction.domain.AuctionStatus;
import com.bugzero.rarego.boundedContext.auction.out.AuctionRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionSettleOneUseCase {

    private final AuctionRepository auctionRepository;
    private final AuctionSettlementSupport support;

    public void execute(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new CustomException(ErrorType.AUCTION_NOT_FOUND));

        // 이미 종료된 경우: 로그만 남기고 'execute' 자체를 종료(Early Return)
        if (auction.getStatus() == AuctionStatus.ENDED) {
            log.warn("경매 {}는 이미 종료되었습니다.", auction.getId());
            return;
        }

        // 그 외 비정상 상태 검증: 예외 발생
        validateAuctionSettlementEligibility(auction);

        // 정산 실행
        support.processSettlement(auctionId);
        log.info("경매 {} 정산 처리 완료", auctionId);
    }

    private void validateAuctionSettlementEligibility(Auction auction) {
        // 진행 중이 아니거나 (SCHEDULED 등)
        if (auction.getStatus() != AuctionStatus.IN_PROGRESS) {
            throw new CustomException(ErrorType.AUCTION_NOT_IN_PROGRESS);
        }
        // 아직 시간이 안 된 경우
        if (auction.getEndTime().isAfter(LocalDateTime.now())) {
            throw new CustomException(ErrorType.AUCTION_NOT_IN_PROGRESS);
        }
    }
}