package bugzero.productservice.product.domain.dto;

import lombok.Builder;

@Builder
public record ProductUpdateResponseDto (
	Long productId,
	Long auctionId
){
}
