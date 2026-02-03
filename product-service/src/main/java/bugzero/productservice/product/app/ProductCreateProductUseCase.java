package bugzero.productservice.product.app;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bugzero.productservice.global.event.EventPublisher;
import bugzero.productservice.product.domain.Product;
import bugzero.productservice.product.domain.ProductImage;
import bugzero.productservice.product.domain.ProductMember;
import bugzero.productservice.product.domain.dto.ProductCreateResponseDto;
import bugzero.productservice.product.out.ProductRepository;
import bugzero.productservice.shared.auction.out.AuctionApiClient;
import bugzero.productservice.shared.product.dto.ProductCreateRequestDto;
import bugzero.productservice.shared.product.dto.ProductImageRequestDto;
import bugzero.productservice.shared.product.event.S3ImageConfirmEvent;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductCreateProductUseCase {
	private final ProductRepository productRepository;
	private final AuctionApiClient auctionApiClient;
	private final ProductSupport productSupport;
	private final EventPublisher eventPublisher;

    @Transactional
    public ProductCreateResponseDto createProduct(String memberUUID, ProductCreateRequestDto dto) {

		ProductMember seller = productSupport.verifyValidateMember(memberUUID);

		Product product = confirmImages(Product.createProduct(seller, dto.name(), dto.category(), dto.description()),
			dto.productImageRequestDto());

        // 부모만 저장 (CascadeType.PERSIST에 의해 자식인 ProductImage들도 자동으로 INSERT됨)
        Product savedProduct = productRepository.save(product);

        //경매정보 생성 요청하는 api
        Long auctionId = auctionApiClient.createAuction(savedProduct.getId(), memberUUID,
                dto.productAuctionRequestDto());

		return ProductCreateResponseDto.builder()
			.productId(savedProduct.getId())
			.auctionId(auctionId)
			.inspectionStatus(savedProduct.getInspectionStatus())
			.build();
	}

	//상품 이미지 url 저장
	private Product confirmImages(Product product, List<ProductImageRequestDto> dtos) {
		//비동기 처리를 위해 원본 temp 경로들을 저장할 리스트
		List<String> tempPaths = new ArrayList<>();

		//상품 이미지 순서 보장 정렬 후 저장
		List<ProductImageRequestDto> images = productSupport.normalizeCreateImageOrder(dtos);

		images.forEach(imageRequestDto -> {
			String originalTempPath = imageRequestDto.imgUrl(); // "temp/uuid_lego.jpg"
			tempPaths.add(originalTempPath);

			product.addImage(ProductImage.createConfirmedImage(product, imageRequestDto.imgUrl(), imageRequestDto.sortOrder()));
		});

		// S3 파일 이동 비동기 호출 (원본 temp 경로 리스트 전달 -> 확정이미지만 S3 product 경로로 옮김)
		eventPublisher.publish(new S3ImageConfirmEvent(tempPaths));

		return product;
	}
}
