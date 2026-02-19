package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

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
import com.bugzero.rarego.product.domain.dto.ProductCreateResponseDto;
import com.bugzero.rarego.product.out.ProductRepository;
import com.bugzero.rarego.shared.product.dto.ProductAuctionCreateDto;
import com.bugzero.rarego.shared.product.dto.ProductCreateRequestDto;
import com.bugzero.rarego.shared.product.dto.ProductImageRequestDto;
import com.bugzero.rarego.shared.product.event.ProductCreateAuctionEvent;
import com.bugzero.rarego.shared.product.event.S3ImageConfirmEvent;
import com.bugzero.rarego.shared.product.type.Category;

@ExtendWith(MockitoExtension.class)
class ProductCreateProductUseCaseTest {

	@InjectMocks
	private ProductCreateProductUseCase useCase;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductSupport productSupport;

	@Mock
	private EventPublisher eventPublisher;

	@Mock
	private OutboxUseCase outboxUseCase; // 추가된 아웃박스 의존성

	@Test
	@DisplayName("성공: 상품 등록 시 아웃박스에 경매 생성 이벤트가 저장되고 S3 확정 이벤트가 발행된다")
	void createProduct_success() {
		// given
		String publicId = "seller-uuid";
		String tempUrl = "temp/starwars.jpg";

		ProductCreateRequestDto request = new ProductCreateRequestDto(
			"스타워즈 시리즈",
			Category.STARWARS,
			"설명",
			new ProductAuctionCreateDto(1000, 7),
			List.of(new ProductImageRequestDto(tempUrl, 0))
		);

		ProductMember seller = ProductMember.builder().id(1L).build();

		given(productSupport.verifyValidateMember(publicId)).willReturn(seller);
		given(productSupport.normalizeCreateImageOrder(anyList())).willReturn(request.productImageRequestDto());

		// Product 저장 시 ID 주입 시뮬레이션
		given(productRepository.save(any(Product.class))).willAnswer(invocation -> {
			Product product = invocation.getArgument(0);
			ReflectionTestUtils.setField(product, "id", 1L);
			return product;
		});

		// when
		ProductCreateResponseDto response = useCase.createProduct(publicId, request);

		// then
		// 1. 아웃박스 저장 검증 (핵심)
		ArgumentCaptor<ProductCreateAuctionEvent> outboxCaptor = ArgumentCaptor.forClass(ProductCreateAuctionEvent.class);
		verify(outboxUseCase).saveOutbox(outboxCaptor.capture());

		ProductCreateAuctionEvent savedEvent = outboxCaptor.getValue();
		assertThat(savedEvent.productId()).isEqualTo(1L);
		assertThat(savedEvent.publicId()).isEqualTo(publicId);

		// 2. S3 이미지 확정 이벤트 발행 검증
		verify(eventPublisher).publish(any(S3ImageConfirmEvent.class));

		// 3. 응답값 확인
		assertThat(response.productId()).isEqualTo(1L);
		verify(productRepository, times(1)).save(any(Product.class));
	}

	@Test
	@DisplayName("실패: 존재하지 않는 회원일 경우 상품 등록이 실패하고 아웃박스도 저장되지 않는다")
	void createProduct_fail_invalidMember() {
		// given
		String invalidPublicId = "invalid-uuid";
		ProductCreateRequestDto request = createSimpleRequest();

		// 존재하지 않는 회원 예외 발생 시뮬레이션
		given(productSupport.verifyValidateMember(invalidPublicId))
			.willThrow(new IllegalArgumentException("존재하지 않는 회원입니다."));

		// when & then
		assertThatThrownBy(() -> useCase.createProduct(invalidPublicId, request))
			.isInstanceOf(IllegalArgumentException.class);

		// 검증: 이후 로직이 실행되지 않아야 함
		verify(productRepository, never()).save(any());
		verify(outboxUseCase, never()).saveOutbox(any());
		verify(eventPublisher, never()).publish(any());
	}

	@Test
	@DisplayName("실패: 상품 정보 저장 중 예외 발생 시 전체 트랜잭션이 실패한다")
	void createProduct_fail_dbError() {
		// given
		String publicId = "seller-uuid";
		ProductCreateRequestDto request = createSimpleRequest();
		ProductMember seller = ProductMember.builder().id(1L).build();

		given(productSupport.verifyValidateMember(publicId)).willReturn(seller);
		given(productSupport.normalizeCreateImageOrder(anyList())).willReturn(request.productImageRequestDto());

		// DB 저장 시 런타임 예외 발생 시뮬레이션
		given(productRepository.save(any(Product.class)))
			.willThrow(new RuntimeException("DB 연결 오류"));

		// when & then
		assertThatThrownBy(() -> useCase.createProduct(publicId, request))
			.isInstanceOf(RuntimeException.class);

		// 검증: DB 저장이 실패했으므로 아웃박스나 이벤트 발행도 호출되지 않아야 함
		verify(outboxUseCase, never()).saveOutbox(any());
		verify(eventPublisher, never()).publish(any());
	}

	private ProductCreateRequestDto createSimpleRequest() {
		return new ProductCreateRequestDto(
			"테스트 상품", Category.STARWARS, "설명",
			new ProductAuctionCreateDto(1000, 7),
			List.of(new ProductImageRequestDto("temp.jpg", 0))
		);
	}
}
