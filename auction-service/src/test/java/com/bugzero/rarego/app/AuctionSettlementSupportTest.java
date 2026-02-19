package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.Auction;
import com.bugzero.rarego.domain.AuctionOrder;
import com.bugzero.rarego.domain.Bid;
import com.bugzero.rarego.domain.event.AuctionFailedEvent;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.AuctionOrderRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.BidRepository;
import com.bugzero.rarego.out.es.ProductSearchClient;
import com.bugzero.rarego.shared.auction.event.AuctionEndedEvent;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.dto.ProductAuctionResponseDto;

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
	private OutboxUseCase outboxUseCase;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private ProductSearchClient productSearchClient;
	@Captor
	private ArgumentCaptor<Object> eventCaptor;

	@Test
	@DisplayName("입찰자가 있을 경우 낙찰 처리가 진행되고 주문 정보와 아웃박스가 저장된다")
	void processSettlement_Success() {
		// given
		Long auctionId = 1L;
		Long bidderId = 20L;
		Integer bidAmount = 50000;
		Long productId = 100L;
		String productName = "테스트 상품";

		Auction auction = Auction.builder()
			.sellerId(10L)
			.productId(productId)
			.endTime(LocalDateTime.now().minusDays(1))
			.durationDays(3)
			.build();
		ReflectionTestUtils.setField(auction, "id", auctionId);
		ReflectionTestUtils.setField(auction, "status", AuctionStatus.IN_PROGRESS);

		Bid winningBid = Bid.builder()
			.bidderId(bidderId)
			.bidAmount(bidAmount)
			.build();

		given(auctionRepository.findByIdWithLock(auctionId)).willReturn(Optional.of(auction));
		given(bidRepository.existsByAuctionId(auctionId)).willReturn(true);
		given(bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(auctionId))
			.willReturn(Optional.of(winningBid));
		given(productSearchClient.getProduct(productId))
			.willReturn(Optional.of(
				ProductAuctionResponseDto.builder()
					.name(productName)
					.build()
			));

		// when
		auctionSettlementSupport.processSettlement(auctionId);

		// then
		verify(auctionRepository).save(any(Auction.class));

		verify(auctionOrderRepository).save(argThat(order ->
			order.getAuctionId().equals(auctionId) &&
				order.getBidderId().equals(bidderId) &&
				order.getFinalPrice() == bidAmount
		));

		// Long 비교는 equals 사용 (== 는 캐싱 범위 밖에서 실패할 수 있음)
		verify(outboxUseCase).saveOutbox(argThat(event ->
			event instanceof AuctionEndedEvent e &&
				e.auctionId().equals(auctionId) &&
				e.winnerId().equals(bidderId) &&
				e.finalPrice().equals(bidAmount)
		));

		verify(eventPublisher, never()).publishEvent(any(AuctionFailedEvent.class));
	}

	@Test
	@DisplayName("입찰자가 없을 경우 유찰 처리가 진행되고 실패 이벤트가 발행된다")
	void processSettlement_Fail() {
		// given
		Long auctionId = 2L;
		Long productId = 200L;
		String productName = "테스트 상품";

		Auction auction = Auction.builder()
			.productId(productId)
			.endTime(LocalDateTime.now().minusDays(1))
			.durationDays(3)
			.build();
		ReflectionTestUtils.setField(auction, "id", auctionId);
		ReflectionTestUtils.setField(auction, "status", AuctionStatus.IN_PROGRESS);

		given(auctionRepository.findByIdWithLock(auctionId)).willReturn(Optional.of(auction));
		given(bidRepository.existsByAuctionId(auctionId)).willReturn(false);
		given(productSearchClient.getProduct(productId))
			.willReturn(Optional.of(
				ProductAuctionResponseDto.builder()
					.name(productName)
					.build()
			));

		// when
		auctionSettlementSupport.processSettlement(auctionId);

		// then
		verify(auctionRepository).save(any(Auction.class));

		// ApplicationEventPublisher.publishEvent는 Object 타입이라 captor로 검증
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		Object capturedEvent = eventCaptor.getValue();
		assertThat(capturedEvent).isInstanceOf(AuctionFailedEvent.class);
		AuctionFailedEvent failedEvent = (AuctionFailedEvent)capturedEvent;
		assertThat(failedEvent.auctionId()).isEqualTo(auctionId);
		assertThat(failedEvent.productId()).isEqualTo(productId);

		verify(auctionOrderRepository, never()).save(any(AuctionOrder.class));
		verify(outboxUseCase, never()).saveOutbox(any());
	}

	@Test
	@DisplayName("경매가 IN_PROGRESS 상태가 아니면 예외가 발생한다")
	void processSettlement_AlreadySettled() {
		// given
		Long auctionId = 3L;

		Auction auction = Auction.builder()
			.productId(100L)
			.endTime(LocalDateTime.now().minusDays(1))
			.durationDays(3)
			.build();
		ReflectionTestUtils.setField(auction, "id", auctionId);
		ReflectionTestUtils.setField(auction, "status", AuctionStatus.ENDED);

		given(auctionRepository.findByIdWithLock(auctionId)).willReturn(Optional.of(auction));

		// when & then
		assertThatThrownBy(() -> auctionSettlementSupport.processSettlement(auctionId))
			.isInstanceOf(CustomException.class);

		verify(auctionRepository, never()).save(any());
		verify(outboxUseCase, never()).saveOutbox(any());
	}

	@Test
	@DisplayName("경매 종료 시간이 아직 지나지 않았으면 예외가 발생한다")
	void processSettlement_NotFinishedYet() {
		// given
		Long auctionId = 4L;

		Auction auction = Auction.builder()
			.productId(100L)
			.endTime(LocalDateTime.now().plusDays(1))  // 아직 종료 안 됨
			.durationDays(3)
			.build();
		ReflectionTestUtils.setField(auction, "id", auctionId);
		ReflectionTestUtils.setField(auction, "status", AuctionStatus.IN_PROGRESS);

		given(auctionRepository.findByIdWithLock(auctionId)).willReturn(Optional.of(auction));

		// when & then
		assertThatThrownBy(() -> auctionSettlementSupport.processSettlement(auctionId))
			.isInstanceOf(CustomException.class);

		verify(auctionRepository, never()).save(any());
		verify(outboxUseCase, never()).saveOutbox(any());
	}

	@Test
	@DisplayName("ES 상품 조회 실패 시에도 낙찰 정산은 정상 완료된다")
	void processSettlement_Success_WhenProductSearchFails() {
		// given
		Long auctionId = 5L;
		Long bidderId = 20L;
		Integer bidAmount = 50000;
		Long productId = 100L;

		Auction auction = Auction.builder()
			.sellerId(10L)
			.productId(productId)
			.endTime(LocalDateTime.now().minusDays(1))
			.durationDays(3)
			.build();
		ReflectionTestUtils.setField(auction, "id", auctionId);
		ReflectionTestUtils.setField(auction, "status", AuctionStatus.IN_PROGRESS);

		Bid winningBid = Bid.builder()
			.bidderId(bidderId)
			.bidAmount(bidAmount)
			.build();

		given(auctionRepository.findByIdWithLock(auctionId)).willReturn(Optional.of(auction));
		given(bidRepository.existsByAuctionId(auctionId)).willReturn(true);
		given(bidRepository.findTopByAuctionIdOrderByBidAmountDescBidTimeAsc(auctionId))
			.willReturn(Optional.of(winningBid));
		// ES 조회 실패 시뮬레이션
		given(productSearchClient.getProduct(productId))
			.willThrow(new RuntimeException("ES connection failed"));

		// when & then - 예외 없이 정상 완료
		assertThatNoException()
			.isThrownBy(() -> auctionSettlementSupport.processSettlement(auctionId));

		verify(auctionOrderRepository).save(any(AuctionOrder.class));
		// productName이 "Unknown Product"로 대체되어 Outbox 저장은 정상 수행
		verify(outboxUseCase).saveOutbox(any());
	}
}
