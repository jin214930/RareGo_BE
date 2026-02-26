package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
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
	private OutboxUseCase outboxUseCase;

	@Mock
	private ProductSearchService productSearchService; // 추가된 ES 서비스 의존성

	private final String publicId = "seller-uuid";

	@Test
	@DisplayName("성공: 모든 과정(DB, Outbox, ES, Event)이 정상적으로 수행된다")
	void createProduct_success() {
		// given
		ProductCreateRequestDto request = createSimpleRequest();
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
		// 1. DB 저장 검증
		verify(productRepository, times(1)).save(any(Product.class));

		// 2. 아웃박스(경매 생성용) 저장 검증
		verify(outboxUseCase).saveOutbox(any(ProductCreateAuctionEvent.class));

		// 3. ES 동기화 호출 검증
		verify(productSearchService).saveBeforeInspection(any(), anyList(), anyInt(), anyInt());

		// 4. S3 이미지 확정 이벤트 발행 검증
		verify(eventPublisher).publish(any(S3ImageConfirmEvent.class));

		assertThat(response.productId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("성공: ES 동기화 중 예외가 발생해도 DB 트랜잭션과 이벤트 발행은 정상 처리된다")
	void createProduct_success_evenIfEsFails() {
		// given
		ProductCreateRequestDto request = createSimpleRequest();
		ProductMember seller = ProductMember.builder().id(1L).build();

		given(productSupport.verifyValidateMember(publicId)).willReturn(seller);
		given(productSupport.normalizeCreateImageOrder(anyList())).willReturn(request.productImageRequestDto());
		given(productRepository.save(any(Product.class))).willAnswer(invocation -> {
			Product product = invocation.getArgument(0);
			ReflectionTestUtils.setField(product, "id", 1L);
			return product;
		});

		// ES 서비스 호출 시 예외 발생 시뮬레이션
		doThrow(new RuntimeException("ES Connection Error"))
			.when(productSearchService).saveBeforeInspection(any(), anyList(), anyInt(), anyInt());

		// when
		ProductCreateResponseDto response = useCase.createProduct(publicId, request);

		// then
		// ES 실패와 상관없이 DB 저장 및 아웃박스는 수행되어야 함 (try-catch 확인)
		verify(productRepository).save(any(Product.class));
		verify(outboxUseCase).saveOutbox(any(ProductCreateAuctionEvent.class));
		verify(eventPublisher).publish(any(S3ImageConfirmEvent.class));

		// 결과 확인
		assertThat(response.productId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("실패: 회원 검증 실패 시 이후 모든 프로세스(DB, Outbox, ES)가 중단된다")
	void createProduct_fail_invalidMember() {
		// given
		ProductCreateRequestDto request = createSimpleRequest();
		given(productSupport.verifyValidateMember(anyString()))
			.willThrow(new IllegalArgumentException("존재하지 않는 회원"));

		// when & then
		assertThatThrownBy(() -> useCase.createProduct(publicId, request))
			.isInstanceOf(IllegalArgumentException.class);

		verify(productRepository, never()).save(any());
		verify(outboxUseCase, never()).saveOutbox(any());
		verify(productSearchService, never()).saveBeforeInspection(any(), anyList(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("실패: DB 저장(save) 단계에서 예외 발생 시 아웃박스와 ES 동기화는 실행되지 않는다")
	void createProduct_fail_dbError() {
		// given
		ProductCreateRequestDto request = createSimpleRequest();
		ProductMember seller = ProductMember.builder().id(1L).build();

		given(productSupport.verifyValidateMember(publicId)).willReturn(seller);
		given(productSupport.normalizeCreateImageOrder(anyList())).willReturn(request.productImageRequestDto());

		// DB 저장 시 런타임 예외 발생
		given(productRepository.save(any(Product.class)))
			.willThrow(new RuntimeException("Database Constraints Error"));

		// when & then
		assertThatThrownBy(() -> useCase.createProduct(publicId, request))
			.isInstanceOf(RuntimeException.class);

		// 검증: DB 실패 시 다음 단계인 Outbox와 ES는 호출되지 않음
		verify(outboxUseCase, never()).saveOutbox(any());
		verify(productSearchService, never()).saveBeforeInspection(any(), anyList(), anyInt(), anyInt());
		verify(eventPublisher, never()).publish(any());
	}

	private ProductCreateRequestDto createSimpleRequest() {
		return new ProductCreateRequestDto(
			"테스트 레고", Category.TECHNIC, "상세설명",
			new ProductAuctionCreateDto(10000, 7),
			List.of(new ProductImageRequestDto("temp.jpg", 0))
		);
	}
}
