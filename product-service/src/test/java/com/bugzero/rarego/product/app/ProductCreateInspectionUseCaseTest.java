package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.product.domain.Inspection;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.product.domain.dto.ProductInspectionRequestDto;
import com.bugzero.rarego.product.domain.dto.ProductInspectionResponseDto;
import com.bugzero.rarego.product.out.InspectionRepository;
import com.bugzero.rarego.shared.auction.out.AuctionApiClient;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.dto.AuctionInfoResponseDto;
import com.bugzero.rarego.shared.product.type.InspectionStatus;
import com.bugzero.rarego.shared.product.type.ProductCondition;

@ExtendWith(MockitoExtension.class)
class ProductCreateInspectionUseCaseTest {
	@Mock
	private InspectionRepository inspectionRepository;
	@Mock
	private ProductSupport productSupport;
	@Mock
	private AuctionApiClient auctionApiClient;
	@Mock
	private ProductSearchService productSearchService;

	@InjectMocks
	private ProductCreateInspectionUseCase useCase;

	@Captor
	private ArgumentCaptor<Inspection> inspectionCaptor;

	private final String ADMIN_UUID = "admin-uuid";
	private final Long ADMIN_INTERNAL_ID = 200L;
	private final Long PRODUCT_ID = 1L;
	private final Long AUCTION_ID = 555L;

	private ProductMember commonAdmin;
	private ProductMember commonSeller;
	private Product commonProduct;

	@BeforeEach
	void setUp() {
		commonAdmin = ProductMember.builder().id(ADMIN_INTERNAL_ID).publicId(ADMIN_UUID).build();
		commonSeller = ProductMember.builder().id(100L).deleted(false).build();

		// 검수 가능한 초기 상태의 상품 (Spy 사용)
		commonProduct = spy(Product.builder()
			.seller(commonSeller)
			.inspectionStatus(InspectionStatus.PENDING)
			.productCondition(ProductCondition.INSPECTION)
			.build());

		lenient().when(commonProduct.getId()).thenReturn(PRODUCT_ID);
		lenient().when(commonProduct.getSeller()).thenReturn(commonSeller);
	}

	@Test
	@DisplayName("성공: 검수 승인 시 기존 ES 데이터를 삭제하고 경매 정보를 포함하여 새롭게 저장한다")
	void createInspection_Success_Approved() {
		// given
		ProductInspectionRequestDto request = createRequest(InspectionStatus.APPROVED, "승인 통과");

		given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(commonProduct);
		given(productSupport.verifyValidateMember(ADMIN_UUID)).willReturn(commonAdmin);

		AuctionInfoResponseDto mockAuctionInfo = createMockAuctionInfo();
		given(auctionApiClient.getAuctionInfo(PRODUCT_ID)).willReturn(mockAuctionInfo);

		given(inspectionRepository.save(any(Inspection.class))).willAnswer(invocation -> {
			Inspection ins = invocation.getArgument(0);
			ReflectionTestUtils.setField(ins, "id", 500L);
			return ins;
		});

		// when
		ProductInspectionResponseDto response = useCase.createInspection(ADMIN_UUID, request);

		// then
		// 1. 상태 동기화 확인
		verify(commonProduct).determineInspection(InspectionStatus.APPROVED);

		// 2. ES 동기화 확인 (삭제 후 객체 전달 저장)
		verify(productSearchService).delete(PRODUCT_ID);
		verify(productSearchService).save(eq(commonProduct), any(), eq(mockAuctionInfo));

		assertThat(response.newStatus()).isEqualTo(InspectionStatus.APPROVED);
	}

	@Test
	@DisplayName("성공: 검수 반려 시 ES 상태를 반려로 업데이트한다")
	void createInspection_Success_Rejected() {
		// given
		ProductInspectionRequestDto request = createRequest(InspectionStatus.REJECTED, "박스 훼손");

		given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(commonProduct);
		given(productSupport.verifyValidateMember(ADMIN_UUID)).willReturn(commonAdmin);

		given(inspectionRepository.save(any(Inspection.class))).willAnswer(invocation -> {
			Inspection ins = invocation.getArgument(0);
			ReflectionTestUtils.setField(ins, "id", 500L);
			return ins;
		});

		// when
		useCase.createInspection(ADMIN_UUID, request);

		// then
		verify(productSearchService).rejectedInspection(PRODUCT_ID);
		verify(productSearchService, never()).delete(any());
		verify(productSearchService, never()).save(any(), any(), any());
	}

	@Test
	@DisplayName("성공: ES 동기화 중 에러가 발생해도 DB 트랜잭션(검수 저장)은 성공해야 한다")
	void createInspection_Success_EvenIfEsFails() {
		// given
		ProductInspectionRequestDto request = createRequest(InspectionStatus.APPROVED, "승인");
		given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(commonProduct);
		given(productSupport.verifyValidateMember(ADMIN_UUID)).willReturn(commonAdmin);

		// save 시 전달받은 객체를 그대로 반환하여 NPE 방지
		given(inspectionRepository.save(any(Inspection.class))).willAnswer(invocation -> {
			Inspection ins = invocation.getArgument(0);
			ReflectionTestUtils.setField(ins, "id", 500L);
			return ins;
		});

		// ES 작업 중 첫 번째 단계인 delete에서 예외 발생 시뮬레이션
		doThrow(new RuntimeException("ES 연결 실패")).when(productSearchService).delete(anyLong());

		// when
		ProductInspectionResponseDto response = useCase.createInspection(ADMIN_UUID, request);

		// then
		verify(inspectionRepository).save(inspectionCaptor.capture());
		Inspection saved = inspectionCaptor.getValue();

		assertThat(response.inspectionId()).isEqualTo(500L);
		assertThat(saved.getInspectorId()).isEqualTo(ADMIN_INTERNAL_ID);

		// ES 예외가 catch 되었으므로 비즈니스 로직은 완수됨
		assertThat(response.newStatus()).isEqualTo(InspectionStatus.APPROVED);
	}

	@Nested
	@DisplayName("실패 케이스 테스트")
	class FailureCases {

		@Test
		@DisplayName("실패: 반려 처리를 하면서 사유(reason)를 적지 않으면 예외가 발생한다")
		void fail_rejected_without_reason() {
			ProductInspectionRequestDto request = createRequest(InspectionStatus.REJECTED, null);

			assertThatThrownBy(() -> useCase.createInspection(ADMIN_UUID, request))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.INSPECTION_REJECT_REASON_REQUIRED);
		}

		@Test
		@DisplayName("실패: 이미 검수가 완료된 상품은 다시 검수할 수 없다")
		void fail_already_completed() {
			// given
			ReflectionTestUtils.setField(commonProduct, "inspectionStatus", InspectionStatus.APPROVED);
			given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(commonProduct);

			ProductInspectionRequestDto request = createRequest(InspectionStatus.APPROVED, "재검수시도");

			// when & then
			assertThatThrownBy(() -> useCase.createInspection(ADMIN_UUID, request))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.INSPECTION_ALREADY_COMPLETED);
		}

		@Test
		@DisplayName("실패: 판매자가 탈퇴한 상태라면 검수를 진행할 수 없다")
		void fail_seller_deleted() {
			// given
			ReflectionTestUtils.setField(commonSeller, "deleted", true);
			given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(commonProduct);

			ProductInspectionRequestDto request = createRequest(InspectionStatus.APPROVED, "승인");

			// when & then
			assertThatThrownBy(() -> useCase.createInspection(ADMIN_UUID, request))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.MEMBER_NOT_FOUND);
		}
	}

	private ProductInspectionRequestDto createRequest(InspectionStatus status, String reason) {
		return new ProductInspectionRequestDto(PRODUCT_ID, status, ProductCondition.MISB, reason);
	}

	private AuctionInfoResponseDto createMockAuctionInfo() {
		return new AuctionInfoResponseDto(
			1L,
			AUCTION_ID,
			10000,
			0,
			AuctionStatus.SCHEDULED,
			LocalDateTime.now(),
			null
		);
	}
}
