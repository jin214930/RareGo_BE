package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

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
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.product.domain.dto.ProductUpdateResponseDto;
import com.bugzero.rarego.shared.product.dto.ProductAuctionUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductImageUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductUpdateDto;
import com.bugzero.rarego.shared.product.event.ProductUpdateAuctionEvent;
import com.bugzero.rarego.shared.product.event.S3ImageConfirmEvent;
import com.bugzero.rarego.shared.product.event.S3ImageDeleteEvent;
import com.bugzero.rarego.shared.product.type.Category;

@ExtendWith(MockitoExtension.class)
class ProductUpdateProductUseCaseTest {

	@Mock
	private ProductSupport productSupport;
	@Mock
	private OutboxUseCase outboxUseCase;
	@Mock
	private EventPublisher eventPublisher;
	@Mock
	private ProductSearchService productSearchService; // 추가된 의존성

	@InjectMocks
	private ProductUpdateProductUseCase useCase;

	private final String PUBLIC_ID = "seller-uuid";
	private final Long PRODUCT_ID = 1L;
	private final Long SELLER_ID = 100L;

	private ProductMember commonSeller;
	private Product spyProduct;

	@BeforeEach
	void setUp() {
		commonSeller = ProductMember.builder()
			.id(SELLER_ID)
			.publicId(PUBLIC_ID)
			.build();

		// 실제 객체를 생성하고 id를 주입한 뒤 spy로 감쌈
		Product product = Product.builder().name("기존 이름").build();
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		spyProduct = spy(product);
	}

	@Test
	@DisplayName("성공: 모든 과정(이미지 처리, 아웃박스 저장, ES 업데이트)이 정상 수행된다")
	void updateProduct_success() {
		// given
		List<ProductImageUpdateDto> imageDtos = List.of(new ProductImageUpdateDto(null, "temp/new.jpg", 1));
		ProductUpdateDto updateDto = createUpdateDto("수정된 이름", imageDtos);

		given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
		given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(spyProduct);
		given(productSupport.normalizeUpdateImageOrder(anyList())).willReturn(imageDtos);

		doReturn(List.of("old.jpg")).when(spyProduct).removeOldImages(anyList());
		doReturn(List.of("new.jpg")).when(spyProduct).processNewImages(anyList());

		// when
		ProductUpdateResponseDto response = useCase.updateProduct(PUBLIC_ID, PRODUCT_ID, updateDto);

		// then
		// 1. 이미지 이벤트 발행 확인
		verify(eventPublisher).publish(any(S3ImageDeleteEvent.class));
		verify(eventPublisher).publish(any(S3ImageConfirmEvent.class));

		// 2. 아웃박스 저장 확인
		verify(outboxUseCase).saveOutbox(any(ProductUpdateAuctionEvent.class));

		// 3. ES 업데이트 호출 확인 (검수 전 상태 업데이트)
		verify(productSearchService).updatedBeforeInspection(eq(spyProduct), any(), anyInt(), anyInt());

		assertThat(response.productId()).isEqualTo(PRODUCT_ID);
	}

	@Test
	@DisplayName("성공: ES 업데이트 중 예외가 발생해도 DB 트랜잭션과 이미지 이벤트는 정상 완료된다")
	void updateProduct_success_evenIfEsFails() {
		// given
		ProductUpdateDto updateDto = createUpdateDto("이름", List.of());
		given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
		given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(spyProduct);

		// ES 예외 시뮬레이션
		doThrow(new RuntimeException("ES Connection Timeout"))
			.when(productSearchService).updatedBeforeInspection(any(), any(), anyInt(), anyInt());

		// when
		ProductUpdateResponseDto response = useCase.updateProduct(PUBLIC_ID, PRODUCT_ID, updateDto);

		// then
		// ES 에러와 상관없이 핵심 로직은 수행되어야 함
		verify(outboxUseCase).saveOutbox(any());
		verify(eventPublisher, atLeastOnce()).publish(any());
		assertThat(response.productId()).isEqualTo(PRODUCT_ID);
	}

	@Nested
	@DisplayName("상품 수정 실패 케이스")
	class FailureCases {

		@Test
		@DisplayName("실패: 권한 체크(isAbleToChange)에서 실패하면 이후 로직이 중단된다")
		void fail_not_allowed_to_change() {
			// given
			ProductUpdateDto updateDto = createUpdateDto("이름", List.of());
			given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
			given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(spyProduct);

			// 권한 체크 실패 시뮬레이션
			doThrow(new RuntimeException("권한 없음"))
				.when(productSupport).isAbleToChange(commonSeller, spyProduct);

			// when & then
			assertThatThrownBy(() -> useCase.updateProduct(PUBLIC_ID, PRODUCT_ID, updateDto))
				.isInstanceOf(RuntimeException.class);

			// 중요: 권한 실패 시 이미지 변경이나 아웃박스 저장이 일어나면 안 됨
			verify(spyProduct, never()).removeOldImages(any());
			verify(outboxUseCase, never()).saveOutbox(any());
			verify(productSearchService, never()).updatedBeforeInspection(any(), any(), anyInt(), anyInt());
		}

		@Test
		@DisplayName("실패: 상품 저장 전 단계에서 예외 발생 시 ES 업데이트는 호출되지 않는다")
		void fail_before_save_logic() {
			// given
			ProductUpdateDto updateDto = createUpdateDto("이름", List.of());
			given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);

			// 상품 조회 단계부터 실패
			given(productSupport.verifyValidateProduct(PRODUCT_ID))
				.willThrow(new RuntimeException("상품 조회 실패"));

			// when & then
			assertThatThrownBy(() -> useCase.updateProduct(PUBLIC_ID, PRODUCT_ID, updateDto))
				.isInstanceOf(RuntimeException.class);

			verify(productSearchService, never()).updatedBeforeInspection(any(), any(), anyInt(), anyInt());
		}
	}

	private ProductUpdateDto createUpdateDto(String name, List<ProductImageUpdateDto> images) {
		return new ProductUpdateDto(
			name,
			Category.STARWARS,
			"설명",
			new ProductAuctionUpdateDto(200L, 1000, 7),
			images
		);
	}
}
