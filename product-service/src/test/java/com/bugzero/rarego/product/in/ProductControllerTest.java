package com.bugzero.rarego.product.in;

import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bugzero.rarego.global.aspect.ResponseAspect;
import com.bugzero.rarego.global.security.MemberPrincipal;
import com.bugzero.rarego.product.app.ProductFacade;
import com.bugzero.rarego.product.domain.dto.ProductCreateResponseDto;
import com.bugzero.rarego.product.domain.dto.ProductUpdateResponseDto;
import com.bugzero.rarego.shared.product.dto.ProductAuctionRequestDto;
import com.bugzero.rarego.shared.product.dto.ProductAuctionUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductCreateRequestDto;
import com.bugzero.rarego.shared.product.dto.ProductImageRequestDto;
import com.bugzero.rarego.shared.product.dto.ProductImageUpdateDto;
import com.bugzero.rarego.shared.product.dto.ProductUpdateDto;
import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.InspectionStatus;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProductController.class) // Controller 지정
@EnableAspectJAutoProxy              // AOP 활성화
@Import(ResponseAspect.class)        // Aspect 빈 등록
class ProductControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProductFacade productFacade;

	@Autowired
	private ObjectMapper objectMapper;

	// 공통 상수
	private final String PUBLIC_ID = "seller-uuid";
	private final Long PRODUCT_ID = 100L;
	private ProductCreateResponseDto defaultResponse;
	private ProductUpdateResponseDto defaultUpdateResponse;

	@BeforeEach
	void setUp() {
		// Fixture 준비
		defaultResponse = ProductCreateResponseDto.builder()
			.productId(PRODUCT_ID)
			.inspectionStatus(InspectionStatus.PENDING)
			.build();

		defaultUpdateResponse = ProductUpdateResponseDto.builder()
			.productId(PRODUCT_ID)
			.auctionId(2L)
			.build();
	}

	// 인증 객체 생성 헬퍼
	private Authentication createAuth(String publicId, String role) {
		MemberPrincipal principal = new MemberPrincipal(publicId, role);
		return new UsernamePasswordAuthenticationToken(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role))
		);
	}

	// --- Helper Methods (Fixture Factory) ---
	private ProductCreateRequestDto createProductRequest(String name, int price, int duration) {
		return new ProductCreateRequestDto(
			name,
			Category.STARWARS,
			"설명",
			new ProductAuctionRequestDto(price, duration),
			List.of(new ProductImageRequestDto("https://s3.image.com/test.jpg", 1))
		);
	}

	private ProductUpdateDto createUpdateBasicInfoDto(String name) {
		return new ProductUpdateDto(
			name,
			Category.STARWARS,
			"설명",
			new ProductAuctionUpdateDto(1L, 1000, 7),
			List.of(new ProductImageUpdateDto(1L, "url", 1))
		);
	}

	// --- 상품 등록 (POST) 테스트 ---

	@Test
	@DisplayName("성공 - 올바른 상품 정보와 memberId가 전달되면 201 응답을 반환한다")
	void createProduct_success() throws Exception {
		// given
		ProductCreateRequestDto requestDto = createProductRequest("스타워즈 레고", 10000, 7);
		given(productFacade.createProduct(eq(PUBLIC_ID), any(ProductCreateRequestDto.class)))
			.willReturn(defaultResponse);

		mockMvc.perform(post("/api/v1/products")
				.with(csrf()) // CSRF 토큰 추가
				.with(authentication(createAuth(PUBLIC_ID, "SELLER"))) // 인증 정보 주입
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(requestDto)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.productId").value(PRODUCT_ID))
			.andExpect(jsonPath("$.data.inspectionStatus").value("PENDING"))
			.andDo(print());
	}

	@Test
	@DisplayName("실패 - 상품명이 비어있거나 경매 기간이 범위를 벗어나면 400 에러를 반환한다")
	void createProduct_fail_validation() throws Exception {
		// given: 상품명이 비어있는 잘못된 요청
		ProductCreateRequestDto invalidRequest = createProductRequest("", 1000, 31); // @NotBlank, @Max 위반

		// when & then
		mockMvc.perform(post("/api/v1/products")
				.with(csrf())
				.with(authentication(createAuth(PUBLIC_ID, "SELLER")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(invalidRequest)))
			.andExpect(status().isBadRequest())
			.andDo(print());
	}

	// --- 상품 수정 (PATCH) 테스트 ---

	@Test
	@DisplayName("성공 - 상품 수정 API 호출 시 성공하면 200 OK와 수정된 상품 ID를 반환한다")
	void updateBasicInfoProduct_success() throws Exception {
		// given
		ProductUpdateDto updateDto = createUpdateBasicInfoDto("수정된 상품명");
		given(productFacade.updateProduct(eq(PUBLIC_ID), eq(PRODUCT_ID), any(ProductUpdateDto.class)))
			.willReturn(defaultUpdateResponse);

		// when & then
		mockMvc.perform(patch("/api/v1/products/{productId}", PRODUCT_ID)
				.with(csrf())
				.with(authentication(createAuth(PUBLIC_ID, "SELLER")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(updateDto)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.productId").value(defaultUpdateResponse.productId()))
			.andExpect(jsonPath("$.data.auctionId").value(defaultUpdateResponse.auctionId()))
			.andDo(print());
	}

	@Test
	@DisplayName("성공 - 상품 삭제 API 호출 시 200 OK를 반환한다")
	void deleteProduct_Success() throws Exception {
		// given
		// Facade 호출 시 아무런 예외도 발생하지 않음을 가정 (void 리턴)
		doNothing().when(productFacade).deleteProduct(eq(PUBLIC_ID), eq(PRODUCT_ID));

		// when & then
		mockMvc.perform(delete("/api/v1/products/{productId}", PRODUCT_ID)
				.with(csrf())
				.with(authentication(createAuth(PUBLIC_ID, "SELLER")))
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.message").exists())
			.andDo(print());

		// Facade가 실제로 호출되었는지 검증
		verify(productFacade).deleteProduct(eq(PUBLIC_ID), eq(PRODUCT_ID));
	}
}