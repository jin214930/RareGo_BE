package com.bugzero.rarego.boundedContext.auction.app;

import com.bugzero.rarego.boundedContext.auction.domain.Auction;
import com.bugzero.rarego.boundedContext.auction.domain.AuctionOrder;
import com.bugzero.rarego.boundedContext.auction.domain.Bid;
import com.bugzero.rarego.boundedContext.auction.event.AuctionFailedEvent;
import com.bugzero.rarego.boundedContext.auction.out.AuctionOrderRepository;
import com.bugzero.rarego.boundedContext.auction.out.AuctionRepository;
import com.bugzero.rarego.boundedContext.auction.out.BidRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
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

    /**
     * 개별 경매 정산 로직 (트랜잭션 분리)
     * 이 메서드는 독립적인 트랜잭션으로 실행되어, 호출부의 루프에서 에러가 나도 commit/rollback이 개별적으로 보장됨
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSettlement(Auction auction) {
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