package com.bugzero.rarego.app;

import org.springframework.stereotype.Service;

import com.bugzero.rarego.domain.AuctionMember;
import com.bugzero.rarego.out.AuctionRepository;
import com.bugzero.rarego.shared.product.dto.ProductAuctionRequestDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuctionCreateAuctionUseCase {
	private final AuctionRepository auctionRepository;
	private final AuctionSupport auctionSupport;

	// 신규상품 경매 정보 생성
	public long createAuction(Long productId,String publicId, ProductAuctionRequestDto productAuctionRequestDto) {
		AuctionMember seller = auctionSupport.getPublicMember(publicId);

		return auctionRepository
			.save(productAuctionRequestDto.toEntity(productId, seller.getId()))
			.getId();
	}
}
