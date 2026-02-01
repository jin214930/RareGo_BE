package com.bugzero.rarego.boundedContext.auction.app;

import com.bugzero.rarego.boundedContext.auction.domain.Auction;
import com.bugzero.rarego.boundedContext.auction.out.AuctionRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionSubscribeStreamUseCase {

    private static final int MAX_SUBSCRIBERS_PER_AUCTION = 1000;

    private final AuctionBidStreamSupport streamSupport;
    private final AuctionRepository auctionRepository;

    public SseEmitter execute(Long auctionId) {
        Auction auction = findAuction(auctionId);
        validateSubscriberLimit(auctionId);

        return streamSupport.subscribe(auctionId, auction.getCurrentPriceOrStartPrice());
    }

    public int getTotalSubscribers() {
        return streamSupport.getTotalSubscribers();
    }

    public int getAuctionSubscribers(Long auctionId) {
        return streamSupport.getAuctionSubscribers(auctionId);
    }

    private Auction findAuction(Long auctionId) {
        return auctionRepository.findById(auctionId)
                .orElseThrow(() -> new CustomException(ErrorType.AUCTION_NOT_FOUND));
    }

    private void validateSubscriberLimit(Long auctionId) {
        int current = streamSupport.getAuctionSubscribers(auctionId);
        if (current >= MAX_SUBSCRIBERS_PER_AUCTION) {
            log.warn("경매 {} 구독자 수 한도 초과 - 현재: {}", auctionId, current);
            throw new CustomException(ErrorType.SERVICE_SUBSCRIBER_LIMIT_EXCEEDED);
        }
    }
}