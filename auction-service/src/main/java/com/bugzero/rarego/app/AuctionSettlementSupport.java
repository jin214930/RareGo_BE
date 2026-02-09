package com.bugzero.rarego.app;

import com.bugzero.rarego.domain.Auction;
import com.bugzero.rarego.domain.AuctionOrder;
import com.bugzero.rarego.domain.Bid;
import com.bugzero.rarego.event.AuctionFailedEvent;
import com.bugzero.rarego.out.AuctionOrderRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.BidRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuctionSettlementSupport {

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final AuctionOrderRepository auctionOrderRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final int BATCH_SIZE = 100;

    @Transactional(readOnly = true)
    public List<Auction> findExpiredAuctions(LocalDateTime now) {
        return auctionRepository.findExpiredInProgressAuctionsWithLock(
                now, PageRequest.of(0, BATCH_SIZE)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSettlement(Long auctionId) {
        // 비관적 락으로 조회: 다른 트랜잭션이 끝날 때까지 대기하여 최신 상태 보장
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new CustomException(ErrorType.AUCTION_NOT_FOUND));

        // 상태 검증: 진행 중이 아니라면 이미 다른 곳에서 정산/철회된 것임
        if (auction.getStatus() != AuctionStatus.IN_PROGRESS) {
            throw new CustomException(ErrorType.AUCTION_NOT_FOUND_OR_ALREADY_SETTLED);
        }

        // 시간 검증
        if (auction.getEndTime().isAfter(LocalDateTime.now())) {
            throw new CustomException(ErrorType.AUCTION_NOT_FINISHED);
        }

        // 낙찰/유찰 처리
        if (bidRepository.existsByAuctionId(auction.getId())) {
            handleSuccess(auction);
        } else {
            handleFail(auction);
        }
    }

    private void handleSuccess(Auction auction) {
        Bid winningBid = bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(auction.getId())
                .orElseThrow(() -> new CustomException(ErrorType.BID_NOT_FOUND));

        auction.end();
        auctionRepository.save(auction);

        auctionOrderRepository.save(
                AuctionOrder.builder()
                        .auctionId(auction.getId())
                        .sellerId(auction.getSellerId())
                        .bidderId(winningBid.getBidderId())
                        .finalPrice(winningBid.getBidAmount())
                        .build()
        );

        eventPublisher.publishEvent(
                new AuctionEndedEvent(
                        auction.getId(),
                        winningBid.getBidderId(),
                        winningBid.getBidAmount(),
                        auction.getProductId()
                )
        );
    }

    private void handleFail(Auction auction) {
        auction.end();
        auctionRepository.save(auction);

        eventPublisher.publishEvent(
                new AuctionFailedEvent(
                        auction.getId(),
                        auction.getProductId()
                )
        );
    }

    // 결과 조회를 위한 helper
    @Transactional(readOnly = true)
    public boolean hasBids(Long auctionId) {
        return bidRepository.existsByAuctionId(auctionId);
    }

    @Transactional(readOnly = true)
    public Bid findWinningBid(Long auctionId) {
        return bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(auctionId)
                .orElseThrow(() -> new CustomException(ErrorType.BID_NOT_FOUND));
    }
}