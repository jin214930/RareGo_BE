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

        // 검증 로직
        validateAuctionStatus(auction);

        // 정산 실행 (Support의 공통 로직 호출)
        support.processSettlement(auction);
        log.info("경매 {} 정산 처리 완료", auctionId);
    }

    private void validateAuctionStatus(Auction auction) {
        if (auction.getStatus() == AuctionStatus.ENDED) {
            log.warn("경매 {}는 이미 종료되었습니다.", auction.getId());
            return;
        }
        if (auction.getStatus() != AuctionStatus.IN_PROGRESS) {
            throw new CustomException(ErrorType.AUCTION_NOT_IN_PROGRESS);
        }
        if (auction.getEndTime().isAfter(LocalDateTime.now())) {
            throw new CustomException(ErrorType.AUCTION_NOT_IN_PROGRESS);
        }
    }
}