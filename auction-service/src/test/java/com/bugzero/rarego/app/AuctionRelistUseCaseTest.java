package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Collections;
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
import com.bugzero.rarego.domain.AuctionMember;
import com.bugzero.rarego.domain.AuctionOrder;
import com.bugzero.rarego.domain.AuctionOrderStatus;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.in.dto.AuctionRelistRequestDto;
import com.bugzero.rarego.in.dto.AuctionRelistResponseDto;
import com.bugzero.rarego.out.AuctionBookmarkRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.es.ProductSearchClient;
import com.bugzero.rarego.shared.auction.event.AuctionRelistedEvent;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.dto.ProductAuctionResponseDto;

@ExtendWith(MockitoExtension.class)
class AuctionRelistUseCaseTest {

	@InjectMocks
	private AuctionRelistUseCase auctionRelistUseCase;

	@Mock
	private AuctionSupport support;
	@Mock
	private AuctionRepository auctionRepository;
	@Mock
	private OutboxUseCase outboxUseCase;
	@Mock
	private ProductSearchClient productSearchClient;
	@Mock
	private AuctionBookmarkRepository auctionBookmarkRepository;
	@Captor
	private ArgumentCaptor<Object> outboxCaptor;

	@Test
	@DisplayName("재경매 성공: 유찰된 상품(주문 없음)을 재등록한다")
	void relistAuction_success_no_order() {
		// given
		Long oldAuctionId = 1L;
		String memberPublicId = "seller_pub_id";
		AuctionRelistRequestDto request = new AuctionRelistRequestDto(20000L, 1000L, 3);
		String productName = "테스트 상품";
		List<Long> bookmarkedMemberIds = List.of(10L, 20L);

		AuctionMember seller = AuctionMember.builder().publicId(memberPublicId).build();
		ReflectionTestUtils.setField(seller, "id", 100L);

		Auction oldAuction = Auction.builder()
			.productId(50L).sellerId(100L).startPrice(10000).durationDays(3).build();
		ReflectionTestUtils.setField(oldAuction, "id", oldAuctionId);
		ReflectionTestUtils.setField(oldAuction, "status", AuctionStatus.ENDED);

		given(support.getPublicMember(memberPublicId)).willReturn(seller);
		given(support.findAuctionById(oldAuctionId)).willReturn(oldAuction);
		willDoNothing().given(support).validateSeller(oldAuction, 100L);
		willDoNothing().given(support).validateAuctionEnded(oldAuction);
		given(support.findOrder(oldAuctionId)).willReturn(Optional.empty());

		Auction savedAuction = Auction.builder()
			.productId(50L).sellerId(100L).startPrice(20000).durationDays(3).build();
		ReflectionTestUtils.setField(savedAuction, "id", 2L);
		ReflectionTestUtils.setField(savedAuction, "status", AuctionStatus.SCHEDULED);

		given(auctionRepository.save(any(Auction.class))).willReturn(savedAuction);
		given(productSearchClient.getProduct(50L))
			.willReturn(Optional.of(ProductAuctionResponseDto.builder().name(productName).build()));
		given(auctionBookmarkRepository.findMemberIdsByAuctionId(oldAuctionId)).willReturn(bookmarkedMemberIds);

		// when
		AuctionRelistResponseDto result = auctionRelistUseCase.relistAuction(oldAuctionId, memberPublicId, request);

		// then
		assertThat(result.newAuctionId()).isEqualTo(2L);
		assertThat(result.productId()).isEqualTo(50L);
		assertThat(oldAuction.getStatus()).isEqualTo(AuctionStatus.RELISTED);
		verify(auctionRepository).save(any(Auction.class));

		// 아웃박스 저장 검증
		verify(outboxUseCase).saveOutbox(outboxCaptor.capture());
		AuctionRelistedEvent event = (AuctionRelistedEvent)outboxCaptor.getValue();
		assertThat(event.newAuctionId()).isEqualTo(2L);
		assertThat(event.productId()).isEqualTo(50L);
		assertThat(event.productName()).isEqualTo(productName);
		assertThat(event.bookmarkedMemberIds()).isEqualTo(bookmarkedMemberIds);
	}

	@Test
	@DisplayName("재경매 성공: 결제 실패(FAILED)된 상품을 재등록한다")
	void relistAuction_success_failed_order() {
		// given
		Long oldAuctionId = 1L;
		Auction oldAuction = Auction.builder().productId(50L).sellerId(100L).durationDays(1).build();
		ReflectionTestUtils.setField(oldAuction, "id", oldAuctionId);
		ReflectionTestUtils.setField(oldAuction, "status", AuctionStatus.ENDED);

		AuctionMember seller = AuctionMember.builder().build();
		ReflectionTestUtils.setField(seller, "id", 100L);

		AuctionOrder failedOrder = AuctionOrder.builder().finalPrice(10000).build();
		ReflectionTestUtils.setField(failedOrder, "status", AuctionOrderStatus.FAILED);

		given(support.getPublicMember(anyString())).willReturn(seller);
		given(support.findAuctionById(anyLong())).willReturn(oldAuction);
		given(support.findOrder(oldAuctionId)).willReturn(Optional.of(failedOrder));

		Auction newAuction = Auction.builder().productId(50L).durationDays(1).build();
		ReflectionTestUtils.setField(newAuction, "status", AuctionStatus.SCHEDULED);
		given(auctionRepository.save(any(Auction.class))).willReturn(newAuction);

		given(productSearchClient.getProduct(anyLong()))
			.willReturn(Optional.of(ProductAuctionResponseDto.builder().name("테스트 상품").build()));
		given(auctionBookmarkRepository.findMemberIdsByAuctionId(oldAuctionId)).willReturn(Collections.emptyList());

		// when
		AuctionRelistResponseDto result =
			auctionRelistUseCase.relistAuction(oldAuctionId, "seller", new AuctionRelistRequestDto(100L, 10L, 1));

		// then
		assertThat(result).isNotNull();
		assertThat(oldAuction.getStatus()).isEqualTo(AuctionStatus.RELISTED);
		verify(outboxUseCase).saveOutbox(any(AuctionRelistedEvent.class));
	}

	@Test
	@DisplayName("재경매 실패: 이미 판매 완료(SUCCESS)된 상품")
	void relistAuction_fail_already_sold() {
		// given
		Long oldAuctionId = 1L;
		Auction oldAuction = Auction.builder().productId(50L).sellerId(100L).durationDays(1).build();
		ReflectionTestUtils.setField(oldAuction, "id", oldAuctionId);

		AuctionMember seller = AuctionMember.builder().build();
		ReflectionTestUtils.setField(seller, "id", 100L);

		AuctionOrder successOrder = AuctionOrder.builder().finalPrice(10000).build();
		ReflectionTestUtils.setField(successOrder, "status", AuctionOrderStatus.SUCCESS);

		given(support.getPublicMember(anyString())).willReturn(seller);
		given(support.findAuctionById(anyLong())).willReturn(oldAuction);
		given(support.findOrder(oldAuctionId)).willReturn(Optional.of(successOrder));

		// when & then
		assertThatThrownBy(() ->
			auctionRelistUseCase.relistAuction(oldAuctionId, "seller", new AuctionRelistRequestDto(100L, 10L, 1))
		)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.AUCTION_ALREADY_SOLD);

		// 아웃박스 저장 안 됨
		verify(outboxUseCase, never()).saveOutbox(any());
	}

	@Test
	@DisplayName("재경매 실패: 판매자가 아닌 경우")
	void relistAuction_fail_seller_mismatch() {
		// given
		Auction oldAuction = Auction.builder().sellerId(100L).durationDays(1).build();
		AuctionMember stranger = AuctionMember.builder().build();
		ReflectionTestUtils.setField(stranger, "id", 999L);

		given(support.getPublicMember(anyString())).willReturn(stranger);
		given(support.findAuctionById(anyLong())).willReturn(oldAuction);
		willThrow(new CustomException(ErrorType.UNAUTHORIZED_AUCTION_SELLER))
			.given(support).validateSeller(oldAuction, 999L);

		// when & then
		assertThatThrownBy(() ->
			auctionRelistUseCase.relistAuction(1L, "stranger", new AuctionRelistRequestDto(100L, 10L, 1))
		)
			.isInstanceOf(CustomException.class)
			.extracting("errorType")
			.isEqualTo(ErrorType.UNAUTHORIZED_AUCTION_SELLER);

		// 아웃박스 저장 안 됨
		verify(outboxUseCase, never()).saveOutbox(any());
	}

	@Test
	@DisplayName("ES 상품 조회 실패 시 productName이 Unknown Product로 대체되어 아웃박스에 저장된다")
	void relistAuction_success_whenProductSearchFails() {
		// given
		Long oldAuctionId = 1L;
		AuctionMember seller = AuctionMember.builder().build();
		ReflectionTestUtils.setField(seller, "id", 100L);

		Auction oldAuction = Auction.builder().productId(50L).sellerId(100L).durationDays(1).build();
		ReflectionTestUtils.setField(oldAuction, "id", oldAuctionId);
		ReflectionTestUtils.setField(oldAuction, "status", AuctionStatus.ENDED);

		given(support.getPublicMember(anyString())).willReturn(seller);
		given(support.findAuctionById(anyLong())).willReturn(oldAuction);
		given(support.findOrder(oldAuctionId)).willReturn(Optional.empty());

		Auction savedAuction = Auction.builder().productId(50L).durationDays(1).build();
		ReflectionTestUtils.setField(savedAuction, "id", 2L);
		ReflectionTestUtils.setField(savedAuction, "status", AuctionStatus.SCHEDULED);
		given(auctionRepository.save(any(Auction.class))).willReturn(savedAuction);

		// ES 조회 실패 시뮬레이션
		given(productSearchClient.getProduct(anyLong()))
			.willThrow(new RuntimeException("ES connection failed"));
		given(auctionBookmarkRepository.findMemberIdsByAuctionId(oldAuctionId)).willReturn(Collections.emptyList());

		// when - 예외 없이 정상 완료
		assertThatNoException().isThrownBy(() ->
			auctionRelistUseCase.relistAuction(oldAuctionId, "seller", new AuctionRelistRequestDto(100L, 10L, 1))
		);

		// then - productName이 Unknown Product로 대체되어 저장
		verify(outboxUseCase).saveOutbox(outboxCaptor.capture());
		AuctionRelistedEvent event = (AuctionRelistedEvent)outboxCaptor.getValue();
		assertThat(event.productName()).isEqualTo("Unknown Product");
		assertThat(oldAuction.getStatus()).isEqualTo(AuctionStatus.RELISTED);
	}
}
