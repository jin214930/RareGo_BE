package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.mockito.BDDMockito.*;

import java.util.Collections;
import java.util.List;

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
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.product.domain.dto.ProductUpdateResponseDto;
import com.bugzero.rarego.shared.product.dto.ProductAuctionUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductImageUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductUpdateDto;
import com.bugzero.rarego.shared.product.event.ProductUpdateAuctionEvent;
import com.bugzero.rarego.shared.product.type.Category;

@ExtendWith(MockitoExtension.class)
class ProductUpdateProductUseCaseTest {

	@Mock
	private ProductSupport productSupport;

	@Mock
	private OutboxUseCase outboxUseCase; // 추가된 아웃박스 의존성

	@Mock
	private EventPublisher eventPublisher;

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

		Product product = Product.builder().name("기존 이름").build();
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);

		spyProduct = spy(product);
	}

	@Test
	@DisplayName("성공: 상품 수정 시 이미지 이벤트가 발행되고 아웃박스에 경매 수정 이벤트가 저장된다")
	void updateProduct_success() {
		// given
		List<ProductImageUpdateDto> imageDtos = List.of(new ProductImageUpdateDto(null, "temp/new.jpg", 1));
		ProductUpdateDto updateDto = createUpdateDto("수정된 이름", imageDtos);

		List<String> deletePaths = List.of("products/old.jpg");
		List<String> confirmPaths = List.of("temp/new.jpg");

		given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
		given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(spyProduct);
		given(productSupport.normalizeUpdateImageOrder(anyList())).willReturn(imageDtos);

		doReturn(deletePaths).when(spyProduct).removeOldImages(anyList());
		doReturn(confirmPaths).when(spyProduct).processNewImages(anyList());

		// when
		ProductUpdateResponseDto response = useCase.updateProduct(PUBLIC_ID, PRODUCT_ID, updateDto);

		// then
		// 1. 아웃박스 저장 검증 (가장 중요한 변경점)
		ArgumentCaptor<ProductUpdateAuctionEvent> outboxCaptor = ArgumentCaptor.forClass(ProductUpdateAuctionEvent.class);
		verify(outboxUseCase).saveOutbox(outboxCaptor.capture());

		ProductUpdateAuctionEvent savedEvent = outboxCaptor.getValue();
		assertThat(savedEvent.productId()).isEqualTo(PRODUCT_ID);
		assertThat(savedEvent.publicId()).isEqualTo(PUBLIC_ID);

		// 2. S3 이미지 관련 이벤트 발행 검증
		verify(eventPublisher, times(2)).publish(any());

		// 3. 결과 확인
		assertThat(response.productId()).isEqualTo(PRODUCT_ID);
	}

	@Test
	@DisplayName("실패: 유효하지 않은 상품일 경우 예외가 발생하고 아웃박스에 저장되지 않는다")
	void updateProduct_fail_invalidProduct() {
		// given
		ProductUpdateDto updateDto = createUpdateDto("이름", Collections.emptyList());

		given(productSupport.verifyValidateMember(PUBLIC_ID)).willReturn(commonSeller);
		// 상품 검증 단계에서 예외 발생 시뮬레이션
		given(productSupport.verifyValidateProduct(PRODUCT_ID))
			.willThrow(new IllegalArgumentException("존재하지 않는 상품입니다."));

		// when & then
		assertThatThrownBy(() -> useCase.updateProduct(PUBLIC_ID, PRODUCT_ID, updateDto))
			.isInstanceOf(IllegalArgumentException.class);

		// 검증: 아웃박스나 이벤트 발행이 호출되지 않아야 함
		verifyNoInteractions(outboxUseCase);
		verifyNoInteractions(eventPublisher);
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
