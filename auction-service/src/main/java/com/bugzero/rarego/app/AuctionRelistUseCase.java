package com.bugzero.rarego.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.Auction;
import com.bugzero.rarego.domain.AuctionMember;
import com.bugzero.rarego.domain.AuctionOrderStatus;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.in.dto.AuctionRelistRequestDto;
import com.bugzero.rarego.in.dto.AuctionRelistResponseDto;
import com.bugzero.rarego.out.AuctionBookmarkRepository;
import com.bugzero.rarego.out.AuctionOrderRepository;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.out.es.ProductSearchClient;
import com.bugzero.rarego.shared.auction.event.AuctionRelistedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionRelistUseCase {

	private final AuctionSupport support; // 기존 Support 재사용
	private final AuctionRepository auctionRepository;
	private final AuctionOrderRepository auctionOrderRepository;
	private final OutboxUseCase outboxUseCase;
	private final ProductSearchClient productSearchClient;
	private final AuctionBookmarkRepository auctionBookmarkRepository;

	@Transactional
	public AuctionRelistResponseDto relistAuction(Long oldAuctionId, String memberPublicId,
		AuctionRelistRequestDto request) {

		AuctionMember seller = support.getPublicMember(memberPublicId);
		Auction oldAuction = support.findAuctionById(oldAuctionId);

		support.validateSeller(oldAuction, seller.getId());
		support.validateAuctionEnded(oldAuction);
		validateCanRelist(oldAuction.getId());

		Auction newAuction = Auction.builder()
			.productId(oldAuction.getProductId())
			.sellerId(seller.getId())
			.startPrice(request.getStartPrice().intValue())
			.startTime(LocalDateTime.now())
			.endTime(LocalDateTime.now().plusDays(request.getDurationDays()))
			.durationDays(request.getDurationDays())
			.build();

		Auction savedAuction = auctionRepository.save(newAuction);
		oldAuction.relist();

		String productName = getProductName(savedAuction.getProductId());
		List<Long> bookmarkedMemberIds = auctionBookmarkRepository.findMemberIdsByAuctionId(oldAuctionId);

		AuctionRelistedEvent event = new AuctionRelistedEvent(
			savedAuction.getProductId(),
			savedAuction.getId(),
			savedAuction.getStartPrice(),
			savedAuction.getStartTime(),
			productName,
			bookmarkedMemberIds
		);
		outboxUseCase.saveOutbox(event);

		log.info("경매 재등록 아웃박스 저장 완료: oldAuctionId={}, newAuctionId={}",
			oldAuctionId, savedAuction.getId());

		return AuctionRelistResponseDto.builder()
			.newAuctionId(savedAuction.getId())
			.productId(savedAuction.getProductId())
			.status(savedAuction.getStatus())
			.message("동일 상품 재경매가 성공적으로 생성되었습니다.")
			.build();
	}

	// ES 호출 실패가 재등록 트랜잭션 롤백으로 이어지지 않도록 예외를 삼킴
	private String getProductName(Long productId) {
		try {
			return productSearchClient.getProduct(productId)
				.map(product -> product.name())
				.orElse("Unknown Product");
		} catch (Exception e) {
			log.warn("상품명 조회 실패, 기본값 사용. productId={}", productId, e);
			return "Unknown Product";
		}
	}

	private void validateCanRelist(Long auctionId) {
		support.findOrder(auctionId).ifPresent(order -> {
			if (order.getStatus() == AuctionOrderStatus.SUCCESS) {
				throw new CustomException(ErrorType.AUCTION_ALREADY_SOLD, "이미 판매가 완료된 상품입니다.");
			}
		});
	}
}
