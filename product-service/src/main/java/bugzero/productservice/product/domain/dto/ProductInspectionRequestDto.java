package bugzero.productservice.product.domain.dto;

import bugzero.productservice.product.domain.Inspection;
import bugzero.productservice.product.domain.Product;
import bugzero.productservice.product.domain.ProductMember;
import bugzero.productservice.shared.product.type.InspectionStatus;
import bugzero.productservice.shared.product.type.ProductCondition;
import jakarta.validation.constraints.NotNull;

public record ProductInspectionRequestDto(
	@NotNull(message = "상품 id는 필수입니다.")
	Long productId,
	@NotNull(message = "검수 결과는 필수입니다.")
	InspectionStatus status,
	@NotNull(message = "상품 상태값은 필수입니다.")
	ProductCondition productCondition,
	String reason
) {
	public Inspection toEntity(Product product, ProductMember seller, Long inspectorId) {
		return Inspection.builder()
			.product(product)
			.seller(seller)
			.inspectorId(inspectorId)
			.inspectionStatus(status)
			.productCondition(productCondition)
			.reason(reason)
			.build();
	}

}
