package bugzero.productservice.product.app;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import bugzero.productservice.global.response.PagedResponseDto;
import bugzero.productservice.product.domain.ProductMember;
import bugzero.productservice.product.domain.dto.ProductCreateResponseDto;
import bugzero.productservice.product.domain.dto.ProductInspectionRequestDto;
import bugzero.productservice.product.domain.dto.ProductInspectionResponseDto;
import bugzero.productservice.product.domain.dto.ProductResponseForInspectionDto;
import bugzero.productservice.product.domain.dto.ProductSearchForInspectionCondition;
import bugzero.productservice.product.domain.dto.ProductUpdateResponseDto;
import bugzero.productservice.shared.member.domain.MemberDto;
import bugzero.productservice.shared.product.dto.ProductCreateRequestDto;
import bugzero.productservice.shared.product.dto.ProductUpdateDto;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductFacade {

	private final ProductCreateProductUseCase productCreateProductUseCase;
	private final ProductCreateInspectionUseCase productCreateInspectionUseCase;
	private final ProductSyncMemberUseCase productSyncMemberUseCase;
	private final ProductUpdateProductUseCase productUpdateProductUseCase;
	private final ProductDeleteProductUseCase productDeleteProductUseCase;
	private final ProductReadProductsForInspectionUseCase productReadProductsForInspectionUseCase;
	private final ProductReadInspectionUseCase productReadInspectionUseCase;

	//판매자용
	public ProductCreateResponseDto createProduct(String memberUUID, ProductCreateRequestDto dto) {
		return productCreateProductUseCase.createProduct(memberUUID, dto);
	}

	public ProductUpdateResponseDto updateProduct(String publicId, Long productId, ProductUpdateDto productUpdateDto) {
		return productUpdateProductUseCase.updateProduct(publicId, productId, productUpdateDto);
	}

	public void deleteProduct(String publicId, Long productId) {
		productDeleteProductUseCase.deleteProduct(publicId, productId);
	}

	//관리자용
	public ProductInspectionResponseDto createInspection(String memberUUID, ProductInspectionRequestDto dto) {
		return productCreateInspectionUseCase.createInspection(memberUUID, dto);
	}

	public ProductInspectionResponseDto readInspection(Long productId) {
		return productReadInspectionUseCase.readInspection(productId);
	}

	public PagedResponseDto<ProductResponseForInspectionDto> readProductsForInspection(
		ProductSearchForInspectionCondition condition, Pageable pageable) {
		return productReadProductsForInspectionUseCase.readProducts(condition, pageable);
	}

	//멤버 동기화
	public ProductMember syncMember(MemberDto member) {
		return productSyncMemberUseCase.syncMember(member);
	}


}
