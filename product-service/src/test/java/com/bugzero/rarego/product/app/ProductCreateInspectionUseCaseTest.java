package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.junit.jupiter.api.Assertions.*;
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

	private ProductMember commonAdmin;
	private ProductMember commonSeller;
	private Product commonProduct;

	@BeforeEach
	void setUp() {
		// 1. 관리자 셋업
		commonAdmin = ProductMember.builder()
			.id(ADMIN_INTERNAL_ID)
			.publicId(ADMIN_UUID)
			.build();

		// 2. 판매자 셋업 (정상 상태)
		commonSeller = ProductMember.builder()
			.id(100L)
			.deleted(false)
			.build();

		// 3. 상품 셋업 (검수 대기 상태)
		commonProduct = Product.builder()
			.seller(commonSeller)
			.inspectionStatus(InspectionStatus.PENDING)
			.productCondition(ProductCondition.INSPECTION)
			.build();
	}

	@Test
	@DisplayName("성공: 모든 검증을 통과하면 검수 정보가 저장되고 상품 상태가 동기화된다")
	void createInspection_Success() {
		// given
		ProductInspectionRequestDto requestDto = createRequest(InspectionStatus.APPROVED, "통과");

		// [1] Product 객체 준비 (ID 설정 필수!)
		Product spyProduct = spy(commonProduct);

		// ★ [핵심 수정] spyProduct가 ID를 반환하도록 설정 (이 부분이 누락되어 null이 넘어감)
		// PRODUCT_ID 상수가 없다면 1L 등으로 대체하세요.
		given(spyProduct.getId()).willReturn(PRODUCT_ID);

		// [2] ProductSupport Mocking
		given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(spyProduct);
		given(productSupport.verifyValidateMember(ADMIN_UUID)).willReturn(commonAdmin);

		// [3] AuctionApiClient Mocking (이전 질문에서 추가한 부분 유지)
		AuctionInfoResponseDto mockAuctionInfo = new AuctionInfoResponseDto(
			1L,
			123L,
			10000,
			0,
			AuctionStatus.SCHEDULED,
			LocalDateTime.now(),
			null
		);
		// anyLong() 대신 구체적인 값을 명시해도 됩니다. (null만 아니면 됨)
		given(auctionApiClient.getAuctionInfo(any())).willReturn(mockAuctionInfo);

		// [4] Repository Mocking
		given(inspectionRepository.save(any(Inspection.class))).willAnswer(invocation -> {
			Inspection ins = invocation.getArgument(0);
			ReflectionTestUtils.setField(ins, "id", 500L);
			return ins;
		});

		// when
		ProductInspectionResponseDto response = useCase.createInspection(ADMIN_UUID, requestDto);

		// then
		verify(inspectionRepository).save(inspectionCaptor.capture());
		Inspection saved = inspectionCaptor.getValue();

		assertThat(response.inspectionId()).isEqualTo(500L);
		assertThat(saved.getInspectorId()).isEqualTo(ADMIN_INTERNAL_ID);
		verify(spyProduct).determineInspection(InspectionStatus.APPROVED);
		verify(spyProduct).determineProductCondition(ProductCondition.MISB);

		// [추가 검증] ES 적재가 올바른 ID로 호출되었는지 확인
		verify(productSearchService).save(eq(spyProduct), any(), eq(mockAuctionInfo));
	}

	@Nested
	@DisplayName("검수 생성 실패 케이스")
	class FailureCases {

		@Test
		@DisplayName("반려 시 사유가 없으면 예외가 발생한다")
		void fail_rejected_without_reason() {
			ProductInspectionRequestDto request = createRequest(InspectionStatus.REJECTED, null);

			assertThatThrownBy(() -> useCase.createInspection(ADMIN_UUID, request))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.INSPECTION_REJECT_REASON_REQUIRED);
		}

		@Test
		@DisplayName("상품의 판매자 정보가 없거나 탈퇴한 회원이면 예외가 발생한다")
		void fail_seller_not_found_or_deleted() {
			// given: 탈퇴한 판매자로 설정
			ProductMember deletedSeller = ProductMember.builder().deleted(true).build();
			commonProduct = Product.builder().seller(deletedSeller).build();

			given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(commonProduct);
			ProductInspectionRequestDto request = createRequest(InspectionStatus.APPROVED, "통과");

			// when & then
			assertThatThrownBy(() -> useCase.createInspection(ADMIN_UUID, request))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.MEMBER_NOT_FOUND);
		}

		@Test
		@DisplayName("이미 검수가 완료된 상품은 다시 검수할 수 없다")
		void fail_already_completed() {
			// given: 검수 상태는 틀렸지만, seller 정보는 정상인 상품을 준비해야 함
			Product completedProduct = Product.builder()
				.seller(commonSeller) // @BeforeEach에서 만든 정상 seller 주입 (필수!)
				.inspectionStatus(InspectionStatus.APPROVED)
				.productCondition(ProductCondition.MISB)
				.build();

			given(productSupport.verifyValidateProduct(PRODUCT_ID)).willReturn(completedProduct);
			ProductInspectionRequestDto request = createRequest(InspectionStatus.APPROVED, "통과");

			// when & then
			// 이제 seller 검증을 통과하고 checkedProductStatus()까지 도달하여 원하는 에러를 던집니다.
			assertThatThrownBy(() -> useCase.createInspection(ADMIN_UUID, request))
				.isInstanceOf(CustomException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.INSPECTION_ALREADY_COMPLETED);
		}

		@Test
		@DisplayName("성공: 검수가 승인되면 DB에 저장하고 ES에 적재한다")
		void createInspection_Success() {
			// given
			String inspectorId = "admin-uuid";
			Long productId = 1L;
			ProductInspectionRequestDto requestDto = new ProductInspectionRequestDto(
				productId, InspectionStatus.APPROVED, ProductCondition.MISB, "승인"
			);

			Product mockProduct = mock(Product.class);
			ProductMember mockSeller = mock(ProductMember.class);
			ProductMember mockAdmin = mock(ProductMember.class);
			Inspection mockInspection = mock(Inspection.class);

			// 1. 기본 조회 Mocking
			when(productSupport.verifyValidateProduct(productId)).thenReturn(mockProduct);
			when(mockProduct.getSeller()).thenReturn(mockSeller);
			when(mockSeller.isDeleted()).thenReturn(false);
			when(productSupport.verifyValidateMember(inspectorId)).thenReturn(mockAdmin);

			// 2. [중요] 유효성 검사 통과를 위한 상태 Mocking (이거 없으면 '검수 완료된 상품' 에러 남)
			when(mockProduct.getInspectionStatus()).thenReturn(InspectionStatus.PENDING);
			when(mockProduct.getProductCondition()).thenReturn(ProductCondition.INSPECTION);
			when(mockProduct.getId()).thenReturn(productId); // ID 반환 설정

			// 3. DB 저장 Mocking
			when(inspectionRepository.save(any())).thenReturn(mockInspection);
			when(mockInspection.getId()).thenReturn(10L);
			when(mockInspection.getProduct()).thenReturn(mockProduct);

			// 4. [중요] ES 적재를 위한 경매 정보 Mocking (이거 없으면 NPE 발생)
			AuctionInfoResponseDto auctionInfo = new AuctionInfoResponseDto(
				1L,
				555L,
				10000,
				0,
				AuctionStatus.SCHEDULED,
				LocalDateTime.now(),
				null
			);
			when(auctionApiClient.getAuctionInfo(productId)).thenReturn(auctionInfo);

			// when
			useCase.createInspection(inspectorId, requestDto);

			// then
			// ES 적재 메서드가 호출되었는지 확인
			verify(productSearchService, times(1)).save(eq(mockProduct), any(), eq(auctionInfo));
		}

		@Test
		@DisplayName("실패: 이미 검수가 완료된 상품이면 예외가 발생한다")
		void createInspection_AlreadyCompleted() {
			// given
			String inspectorId = "admin-uuid";
			Long productId = 1L;
			ProductInspectionRequestDto requestDto = new ProductInspectionRequestDto(
				productId, InspectionStatus.APPROVED, ProductCondition.MISB, "승인"
			);

			Product mockProduct = mock(Product.class);
			ProductMember mockSeller = mock(ProductMember.class);

			when(productSupport.verifyValidateProduct(productId)).thenReturn(mockProduct);
			when(mockProduct.getSeller()).thenReturn(mockSeller);

			// [중요] 이미 승인된 상태로 설정
			when(mockProduct.getInspectionStatus()).thenReturn(InspectionStatus.APPROVED);
			// OR
			// when(mockProduct.getProductCondition()).thenReturn(ProductCondition.NEW);

			// when & then: 예외가 발생하는지 확인
			assertThrows(CustomException.class, () ->
				useCase.createInspection(inspectorId, requestDto)
			);
		}
	}

	// Helper: 반복되는 Request 생성을 분리
	private ProductInspectionRequestDto createRequest(InspectionStatus status, String reason) {
		return new ProductInspectionRequestDto(PRODUCT_ID, status, ProductCondition.MISB, reason);
	}
}
