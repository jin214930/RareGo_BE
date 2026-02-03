package com.bugzero.rarego.boundedContext.auction.app;

import org.springframework.stereotype.Service;

import com.bugzero.rarego.boundedContext.auction.domain.Auction;
import com.bugzero.rarego.boundedContext.auction.domain.AuctionMember;
import com.bugzero.rarego.boundedContext.auction.out.AuctionRepository;
import com.bugzero.rarego.shared.product.dto.ProductAuctionRequestDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuctionCreateAuctionUseCase {
	private final AuctionRepository auctionRepository;
	private final AuctionSupport auctionSupport;

	// 신규상품 경매 정보 생성
	public long createAuction(Long productId,String publicId, ProductAuctionRequestDto dto) {
		AuctionMember seller = auctionSupport.getPublicMember(publicId);

		return auctionRepository
			.save(Auction.builder()
				.productId(productId)
				.sellerId(seller.getId())
				.startPrice(dto.startPrice())
				.durationDays(dto.durationDays())
				.build())
			.getId();


	}
}
