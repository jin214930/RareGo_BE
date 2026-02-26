package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForInterfaceTypes.*;
import static org.mockito.BDDMockito.*;

import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
	private OutboxUseCase outboxUseCase;
	@Mock
	private EventPublisher eventPublisher;
	@Mock
	private ProductSearchService productSearchService; // 추가된 의존성

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
	@DisplayName("성공: 모든 삭제 과정(소프트 삭제, S3 이벤트, 아웃박스, ES 삭제)이 정상 수행된다")
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

		// 2. 아웃박스 저장 검증
		verify(outboxUseCase).saveOutbox(any(ProductDeleteAuctionEvent.class));

		// 3. S3 이미지 삭제 이벤트 발행 검증
		verify(eventPublisher).publish(any(S3ImageDeleteEvent.class));

		// 4. ES 데이터 삭제 호출 검증
		verify(productSearchService).delete(PRODUCT_ID);

		verify(productSupport).isAbleToDelete(commonSeller, spyProduct);
	}

	@Test
	@DisplayName("성공: ES 삭제 중 예외가 발생해도 DB 트랜잭션과 이미지 삭제 이벤트는 정상 처리된다")
	void deleteProduct_Success_EvenIfEsFails() {
		// given
		given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
		given(productSupport.findByIdWithImages(PRODUCT_ID)).willReturn(spyProduct);

		// ES 삭제 시 예외 발생 시뮬레이션
		doThrow(new RuntimeException("ES Connection Error"))
			.when(productSearchService).delete(PRODUCT_ID);

		// when
		useCase.deleteProduct(PUBLIC_ID, PRODUCT_ID);

		// then
		// ES 에러와 상관없이 DB 관련 로직은 수행되어야 함 (try-catch 확인)
		verify(spyProduct).softDelete();
		verify(outboxUseCase).saveOutbox(any());
		verify(eventPublisher).publish(any());
	}

	@Nested
	@DisplayName("상품 삭제 실패 케이스")
	class FailureCases {

		@Test
		@DisplayName("실패: 삭제 권한이 없으면 아웃박스 저장 및 ES 삭제를 수행하지 않는다")
		void deleteProduct_Fail_Unauthorized() {
			// given
			given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
			given(productSupport.findByIdWithImages(PRODUCT_ID)).willReturn(spyProduct);

			// 권한 예외 발생 시뮬레이션
			doThrow(new RuntimeException("권한이 없습니다."))
				.when(productSupport).isAbleToDelete(any(), any());

			// when & then
			assertThatThrownBy(() -> useCase.deleteProduct(PUBLIC_ID, PRODUCT_ID))
				.isInstanceOf(RuntimeException.class);

			// 검증: 예외 발생 시 이후 로직이 실행되지 않아야 함
			verify(spyProduct, never()).softDelete();
			verifyNoInteractions(outboxUseCase);
			verifyNoInteractions(eventPublisher);
			verifyNoInteractions(productSearchService);
		}

		@Test
		@DisplayName("실패: 상품 조회 실패 시 이후 모든 프로세스가 중단된다")
		void deleteProduct_Fail_NotFound() {
			// given
			given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
			given(productSupport.findByIdWithImages(PRODUCT_ID))
				.willThrow(new RuntimeException("상품을 찾을 수 없습니다."));

			// when & then
			assertThatThrownBy(() -> useCase.deleteProduct(PUBLIC_ID, PRODUCT_ID))
				.isInstanceOf(RuntimeException.class);

			verify(outboxUseCase, never()).saveOutbox(any());
			verify(productSearchService, never()).delete(anyLong());
		}
	}
}
