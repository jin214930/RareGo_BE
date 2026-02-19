package com.bugzero.rarego.product.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.global.event.EventPublisher;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.ProductImage;
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.shared.product.event.ProductDeleteAuctionEvent;
import com.bugzero.rarego.shared.product.event.S3ImageDeleteEvent;

@ExtendWith(MockitoExtension.class)
class ProductDeleteProductUseCaseTest {

	@Mock
	private ProductSupport productSupport;

	@Mock
	private OutboxUseCase outboxUseCase; // AuctionApiClient 대신 OutboxUseCase 주입

	@Mock
	private EventPublisher eventPublisher;

	@InjectMocks
	private ProductDeleteProductUseCase useCase;

	private final String PUBLIC_ID = "seller-uuid";
	private final Long PRODUCT_ID = 100L;

	private ProductMember commonSeller;
	private Product spyProduct;

	@BeforeEach
	void setUp() {
		commonSeller = ProductMember.builder()
			.id(1L)
			.publicId(PUBLIC_ID)
			.build();

		// 테스트용 이미지들을 포함한 상품 생성
		Product product = Product.builder()
			.name("삭제될 상품")
			.images(new ArrayList<>())
			.build();

		ProductImage image1 = ProductImage.createConfirmedImage(product, "products/image1.jpg", 0);
		ProductImage image2 = ProductImage.createConfirmedImage(product, "products/image2.jpg", 1);

		product.getImages().add(image1);
		product.getImages().add(image2);

		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);

		spyProduct = spy(product);
	}

	@Test
	@DisplayName("성공: 상품 삭제 시 소프트 삭제를 수행하고 아웃박스에 삭제 이벤트를 저장한다")
	void deleteProduct_Success() {
		// given
		given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
		given(productSupport.findByIdWithImages(PRODUCT_ID)).willReturn(spyProduct);

		// when
		useCase.deleteProduct(PUBLIC_ID, PRODUCT_ID);

		// then
		// 1. 엔티티 상태 변화 검증
		verify(spyProduct).softDelete();
		assertThat(spyProduct.getImages()).isEmpty();

		// 2. 아웃박스 저장 검증 (핵심 변경 사항)
		ArgumentCaptor<ProductDeleteAuctionEvent> outboxCaptor = ArgumentCaptor.forClass(ProductDeleteAuctionEvent.class);
		verify(outboxUseCase).saveOutbox(outboxCaptor.capture());

		ProductDeleteAuctionEvent savedEvent = outboxCaptor.getValue();
		assertThat(savedEvent.productId()).isEqualTo(PRODUCT_ID);
		assertThat(savedEvent.publicId()).isEqualTo(PUBLIC_ID);

		// 3. S3 이미지 삭제 이벤트 발행 검증
		ArgumentCaptor<S3ImageDeleteEvent> s3EventCaptor = ArgumentCaptor.forClass(S3ImageDeleteEvent.class);
		verify(eventPublisher).publish(s3EventCaptor.capture());
		assertThat(s3EventCaptor.getValue().paths()).containsExactly("products/image1.jpg", "products/image2.jpg");

		verify(productSupport).isAbleToDelete(commonSeller, spyProduct);
	}

	@Test
	@DisplayName("실패: 삭제 권한이 없으면 아웃박스에 저장하지 않고 이벤트도 발행하지 않는다")
	void deleteProduct_Fail_Unauthorized() {
		// given
		given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
		given(productSupport.findByIdWithImages(PRODUCT_ID)).willReturn(spyProduct);

		// 권한 예외 발생 시뮬레이션
		willThrow(new RuntimeException("권한이 없습니다."))
			.given(productSupport).isAbleToDelete(any(), any());

		// when & then
		assertThatThrownBy(() -> useCase.deleteProduct(PUBLIC_ID, PRODUCT_ID))
			.isInstanceOf(RuntimeException.class);

		// 검증: 예외 발생 시 아웃박스 및 이벤트 발행이 호출되지 않아야 함
		verify(spyProduct, never()).softDelete();
		verifyNoInteractions(outboxUseCase);
		verifyNoInteractions(eventPublisher);
	}
}
