package com.bugzero.rarego.app;

import com.bugzero.rarego.domain.Auction;
import com.bugzero.rarego.domain.AuctionOrder;
import com.bugzero.rarego.domain.AuctionOutbox;
import com.bugzero.rarego.domain.Bid;
import com.bugzero.rarego.domain.event.AuctionFailedEvent;
import com.bugzero.rarego.out.AuctionOrderRepository;
import com.bugzero.rarego.out.AuctionOutboxRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.BidRepository;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuctionSettlementSupportTest {

    @InjectMocks
    private AuctionSettlementSupport auctionSettlementSupport;

    @Mock
    private AuctionRepository auctionRepository;
    @Mock
    private BidRepository bidRepository;
    @Mock
    private AuctionOrderRepository auctionOrderRepository;
    @Mock
    private AuctionOutboxRepository auctionOutboxRepository;
    @Mock
    private AuctionOutboxProcessor auctionOutboxProcessor;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("입찰자가 있을 경우 낙찰 처리가 진행되고 주문 정보와 아웃박스가 저장된다")
    void processSettlement_Success() {
        // given
        Long auctionId = 1L;
        Auction auction = Auction.builder()
                .sellerId(10L)
                .productId(100L)
                .endTime(LocalDateTime.now().minusDays(1))
                .durationDays(3)
                .build();

        ReflectionTestUtils.setField(auction, "id", auctionId);
        ReflectionTestUtils.setField(auction, "status", AuctionStatus.IN_PROGRESS);

        Bid winningBid = Bid.builder()
                .bidderId(20L)
                .bidAmount(50000)
                .build();

        given(auctionRepository.findByIdWithLock(auctionId)).willReturn(Optional.of(auction));
        given(bidRepository.existsByAuctionId(auctionId)).willReturn(true);
        given(bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(auctionId))
                .willReturn(Optional.of(winningBid));

        // Mocking for Outbox save
        AuctionOutbox mockOutbox = mock(AuctionOutbox.class);
        given(auctionOutboxRepository.save(any(AuctionOutbox.class))).willReturn(mockOutbox);

        // when
        auctionSettlementSupport.processSettlement(auctionId);

        // then
        verify(auctionRepository).save(any(Auction.class));
        verify(auctionOrderRepository).save(any(AuctionOrder.class));
        verify(auctionOutboxRepository).save(any(AuctionOutbox.class));
        verify(eventPublisher, never()).publishEvent(any(AuctionFailedEvent.class));
    }

    @Test
    @DisplayName("입찰자가 없을 경우 유찰 처리가 진행되고 실패 이벤트가 발행된다")
    void processSettlement_Fail() {
        // given
        Long auctionId = 2L;
        Auction auction = Auction.builder()
                .productId(200L)
                .endTime(LocalDateTime.now().minusDays(1))
                .durationDays(3)
                .build();

        ReflectionTestUtils.setField(auction, "id", auctionId);
        ReflectionTestUtils.setField(auction, "status", AuctionStatus.IN_PROGRESS);

        given(auctionRepository.findByIdWithLock(auctionId)).willReturn(Optional.of(auction));
        given(bidRepository.existsByAuctionId(auctionId)).willReturn(false);

        // when
        auctionSettlementSupport.processSettlement(auctionId);

        // then
        verify(auctionRepository).save(any(Auction.class));
        verify(eventPublisher).publishEvent(any(AuctionFailedEvent.class));
        verify(auctionOrderRepository, never()).save(any(AuctionOrder.class));
        verify(auctionOutboxRepository, never()).save(any(AuctionOutbox.class));
    }
}