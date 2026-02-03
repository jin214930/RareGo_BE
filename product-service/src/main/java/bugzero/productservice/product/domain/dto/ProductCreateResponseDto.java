package bugzero.productservice.product.domain.dto;

import bugzero.productservice.shared.product.type.InspectionStatus;
import lombok.Builder;

@Builder
public record ProductCreateResponseDto(
	long productId,
	long auctionId,
	InspectionStatus inspectionStatus
) {
}
