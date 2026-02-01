package com.bugzero.rarego.boundedContext.auction.in;

import com.bugzero.rarego.boundedContext.auction.app.AuctionFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 경매 실시간 입찰 스트림 API
 */
@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
@Slf4j
public class AuctionBidStreamController {

    private final AuctionFacade auctionFacade;

    // 경매 실시간 입찰 이벤트 구독
    @GetMapping(value = "/{auctionId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable Long auctionId) {
        log.info("경매 {} 스트림 구독 요청", auctionId);
        return auctionFacade.subscribeAuctionStream(auctionId);
    }

    // 모니터링용 - 전체 구독자 수 조회
    @GetMapping("/subscribers/count")
    public int getTotalSubscribers() {
        return auctionFacade.getTotalSubscribers();
    }

    // 모니터링용 - 특정 경매 구독자 수 조회
    @GetMapping("/{auctionId}/subscribers/count")
    public int getAuctionSubscribers(@PathVariable Long auctionId) {
        return auctionFacade.getAuctionSubscribers(auctionId);
    }
}