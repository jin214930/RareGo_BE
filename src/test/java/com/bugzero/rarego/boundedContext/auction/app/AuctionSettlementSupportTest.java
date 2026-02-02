package com.bugzero.rarego.boundedContext.auction.app;

import com.bugzero.rarego.boundedContext.auction.domain.Auction;
import com.bugzero.rarego.boundedContext.auction.domain.AuctionOrder;
import com.bugzero.rarego.boundedContext.auction.domain.AuctionStatus;
import com.bugzero.rarego.boundedContext.auction.domain.Bid;
import com.bugzero.rarego.boundedContext.auction.event.AuctionFailedEvent;
import com.bugzero.rarego.boundedContext.auction.out.AuctionOrderRepository;
import com.bugzero.rarego.boundedContext.auction.out.AuctionRepository;
import com.bugzero.rarego.boundedContext.auction.out.BidRepository;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuctionSettlementSupportTest {

    // 1. 필요한 모든 의존성을 @Mock으로 선언합니다.
    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionOrderRepository auctionOrderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    // 2. 위 Mock들을 주입받을 대상입니다.
    @InjectMocks
    private AuctionSettlementSupport support;

    private Auction auction;

    @BeforeEach
    void setUp() {
        // Auction 객체 생성 시 durationDays 등 필수 필드 누락 주의!
        auction = Auction.builder()
                .sellerId(1L)
                .productId(100L)
                .startPrice(10000)
                .durationDays(1)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().minusMinutes(1))
                .build();

        ReflectionTestUtils.setField(auction, "id", 1L);
        ReflectionTestUtils.setField(auction, "status", AuctionStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("입찰자가 있는 경우: 낙찰 처리되고 주문이 생성된다")
    void processSettlement_success_with_bid() {
        // given
        Bid winningBid = Bid.builder()
                .bidderId(10L)
                .bidAmount(50000)
                .build();

        given(bidRepository.existsByAuctionId(1L)).willReturn(true);
        given(bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(1L))
                .willReturn(Optional.of(winningBid));

        // when
        support.processSettlement(auction);

        // then
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        verify(auctionRepository).save(auction); // 이 부분이 null이 아니어야 함
        verify(auctionOrderRepository).save(any(AuctionOrder.class));
        verify(eventPublisher).publishEvent(any(AuctionEndedEvent.class));
    }

    @Test
    @DisplayName("입찰자가 없는 경우: 유찰 처리되고 실패 이벤트가 발행된다")
    void processSettlement_fail_no_bid() {
        // given
        given(bidRepository.existsByAuctionId(1L)).willReturn(false);

        // when
        support.processSettlement(auction);

        // then
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        verify(auctionRepository).save(auction);
        verify(auctionOrderRepository, never()).save(any());
        verify(eventPublisher).publishEvent(any(AuctionFailedEvent.class));
    }
}