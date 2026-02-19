package com.bugzero.rarego.in;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.Auction;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.AuctionBookmarkRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.es.ProductSearchClient;
import com.bugzero.rarego.shared.auction.event.AuctionStartedEvent;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.dto.ProductAuctionResponseDto;

@ExtendWith(MockitoExtension.class)
class AuctionStartSchedulerTest {

	@InjectMocks
	private AuctionStartScheduler auctionStartScheduler;

	@Mock
	private AuctionRepository auctionRepository;
	@Mock
	private AuctionBookmarkRepository auctionBookmarkRepository;
	@Mock
	private OutboxUseCase outboxUseCase;
	@Mock
	private ProductSearchClient productSearchClient;

	@Captor
	private ArgumentCaptor<Object> outboxCaptor;

	void injectSelf() {
		ReflectionTestUtils.setField(auctionStartScheduler, "self", auctionStartScheduler);
	}

	@Test
	@DisplayName("시작 시간이 된 경매가 있으면 start() 호출 후 아웃박스에 저장된다")
	void autoStartAuctions_Success() {
		injectSelf();

		Long auctionId = 1L;
		Long productId = 100L;
		String productName = "테스트 상품";
		List<Long> bookmarkedMemberIds = List.of(10L, 20L, 30L);
		LocalDateTime startTime = LocalDateTime.now().minusMinutes(1);

		Auction auction = Auction.builder()
			.productId(productId)
			.sellerId(1L)
			.startPrice(10000)
			.durationDays(3)
			.build();
		ReflectionTestUtils.setField(auction, "id", auctionId);
		ReflectionTestUtils.setField(auction, "status", AuctionStatus.SCHEDULED);
		ReflectionTestUtils.setField(auction, "startTime", startTime);

		given(auctionRepository.findAllByStatusAndStartTimeBefore(eq(AuctionStatus.SCHEDULED), any()))
			.willReturn(List.of(auction));
		given(auctionRepository.findByIdWithLock(auctionId))
			.willReturn(Optional.of(auction));
		given(auctionBookmarkRepository.findMemberIdsByAuctionId(auctionId))
			.willReturn(bookmarkedMemberIds);
		given(productSearchClient.getProduct(productId))
			.willReturn(Optional.of(ProductAuctionResponseDto.builder().name(productName).build()));

		auctionStartScheduler.autoStartAuctions();

		verify(outboxUseCase).saveOutbox(outboxCaptor.capture());
		AuctionStartedEvent event = (AuctionStartedEvent)outboxCaptor.getValue();
		assertThat(event.auctionId()).isEqualTo(auctionId);
		assertThat(event.productId()).isEqualTo(productId);
		assertThat(event.productName()).isEqualTo(productName);
		assertThat(event.bookmarkedMemberIds()).isEqualTo(bookmarkedMemberIds);
		assertThat(event.startedAt()).isEqualTo(startTime);
	}

	@Test
	@DisplayName("시작할 경매가 없으면 아무 처리도 하지 않는다")
	void autoStartAuctions_NoPendingAuctions() {
		injectSelf();

		given(auctionRepository.findAllByStatusAndStartTimeBefore(eq(AuctionStatus.SCHEDULED), any()))
			.willReturn(List.of());

		auctionStartScheduler.autoStartAuctions();

		verify(outboxUseCase, never()).saveOutbox(any());
		verify(auctionBookmarkRepository, never()).findMemberIdsByAuctionId(any());
	}

	@Test
	@DisplayName("ES 상품 조회 실패 시 productName이 Unknown Product로 대체되어 아웃박스에 저장된다")
	void autoStartAuctions_ProductSearchFails() {
		injectSelf();

		Long auctionId = 2L;
		Long productId = 200L;
		LocalDateTime startTime = LocalDateTime.now().minusMinutes(1);

		Auction auction = Auction.builder()
			.productId(productId)
			.sellerId(1L)
			.startPrice(10000)
			.durationDays(3)
			.build();
		ReflectionTestUtils.setField(auction, "id", auctionId);
		ReflectionTestUtils.setField(auction, "status", AuctionStatus.SCHEDULED);
		ReflectionTestUtils.setField(auction, "startTime", startTime);

		given(auctionRepository.findAllByStatusAndStartTimeBefore(eq(AuctionStatus.SCHEDULED), any()))
			.willReturn(List.of(auction));
		given(auctionRepository.findByIdWithLock(auctionId))
			.willReturn(Optional.of(auction));
		given(auctionBookmarkRepository.findMemberIdsByAuctionId(auctionId))
			.willReturn(List.of());
		given(productSearchClient.getProduct(productId))
			.willThrow(new RuntimeException("ES connection failed"));

		assertThatNoException().isThrownBy(() -> auctionStartScheduler.autoStartAuctions());

		verify(outboxUseCase).saveOutbox(outboxCaptor.capture());
		AuctionStartedEvent event = (AuctionStartedEvent)outboxCaptor.getValue();
		assertThat(event.productName()).isEqualTo("Unknown Product");
	}

	@Test
	@DisplayName("여러 경매 중 하나가 실패해도 나머지는 정상 처리된다")
	void autoStartAuctions_PartialFailure() {
		injectSelf();

		Long auctionId1 = 1L;
		Long auctionId2 = 2L;
		Long productId = 100L;

		Auction auction1 = Auction.builder()
			.productId(productId).sellerId(1L).startPrice(10000).durationDays(3).build();
		ReflectionTestUtils.setField(auction1, "id", auctionId1);
		ReflectionTestUtils.setField(auction1, "status", AuctionStatus.SCHEDULED);
		ReflectionTestUtils.setField(auction1, "startTime", LocalDateTime.now().minusMinutes(1));

		Auction auction2 = Auction.builder()
			.productId(productId).sellerId(1L).startPrice(10000).durationDays(3).build();
		ReflectionTestUtils.setField(auction2, "id", auctionId2);
		ReflectionTestUtils.setField(auction2, "status", AuctionStatus.SCHEDULED);
		ReflectionTestUtils.setField(auction2, "startTime", LocalDateTime.now().minusMinutes(1));

		given(auctionRepository.findAllByStatusAndStartTimeBefore(eq(AuctionStatus.SCHEDULED), any()))
			.willReturn(List.of(auction1, auction2));
		// auction1 - findByIdWithLock에서 예외 발생
		given(auctionRepository.findByIdWithLock(auctionId1))
			.willThrow(new RuntimeException("DB error"));
		// auction2 - 정상 처리
		given(auctionRepository.findByIdWithLock(auctionId2))
			.willReturn(Optional.of(auction2));
		given(auctionBookmarkRepository.findMemberIdsByAuctionId(auctionId2))
			.willReturn(List.of());
		given(productSearchClient.getProduct(productId))
			.willReturn(Optional.of(ProductAuctionResponseDto.builder().name("상품").build()));

		assertThatNoException().isThrownBy(() -> auctionStartScheduler.autoStartAuctions());

		// auction2만 아웃박스 저장
		verify(outboxUseCase, times(1)).saveOutbox(any());
	}
}
