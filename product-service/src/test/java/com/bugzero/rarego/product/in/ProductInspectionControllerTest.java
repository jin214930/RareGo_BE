package com.bugzero.rarego.product.in;

import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bugzero.rarego.global.aspect.ResponseAspect;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.global.response.PagedResponseDto;
import com.bugzero.rarego.global.security.MemberPrincipal;
import com.bugzero.rarego.product.app.ProductFacade;
import com.bugzero.rarego.product.domain.dto.ProductInspectionRequestDto;
import com.bugzero.rarego.product.domain.dto.ProductInspectionResponseDto;
import com.bugzero.rarego.product.domain.dto.ProductResponseForInspectionDto;
import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.InspectionStatus;
import com.bugzero.rarego.shared.product.type.ProductCondition;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProductInspectionController.class) // Controller 지정
@EnableAspectJAutoProxy              // AOP 활성화
@Import(ResponseAspect.class)        // Aspect 빈 등록
class ProductInspectionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProductFacade productFacade;

	@Autowired
	private ObjectMapper objectMapper;

	private final Long PRODUCT_ID = 1L;
	private final String PUBLIC_ID = "seller-uuid";

	// 인증 객체 생성 헬퍼
	private Authentication createAuth(String publicId, String role) {
		MemberPrincipal principal = new MemberPrincipal(publicId, role);
		return new UsernamePasswordAuthenticationToken(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role))
		);
	}

	@Test
	@DisplayName("성공 - 상품 검수 생성 API가 정상적으로 호출되면 201 Created를 반환한다")
	void createProductInspection_success() throws Exception {
		// given
		ProductInspectionRequestDto requestDto = new ProductInspectionRequestDto(
			PRODUCT_ID, InspectionStatus.APPROVED, ProductCondition.MISB,
			"검수 승인 완료"
		);

		ProductInspectionResponseDto responseDto = ProductInspectionResponseDto.builder()
			.inspectionId(500L)
			.productId(PRODUCT_ID)
			.newStatus(InspectionStatus.APPROVED)
			.productCondition(ProductCondition.MISB)
			.reason("검수 승인 완료")
			.build();

		given(productFacade.createInspection(eq(PUBLIC_ID), any(ProductInspectionRequestDto.class)))
			.willReturn(responseDto);

		// when & then
		mockMvc.perform(post("/api/v1/products/inspections")
				.with(csrf()) // CSRF 토큰
				.with(authentication(createAuth(PUBLIC_ID, "ADMIN"))) // 관리자 권한 가정
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(requestDto)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(201))
			.andExpect(jsonPath("$.data.inspectionId").value(500L))
			.andExpect(jsonPath("$.data.newStatus").value("APPROVED"))
			.andExpect(jsonPath("$.data.productCondition").value("MISB"))
			.andDo(print());
	}

	@Test
	@DisplayName("실패 - 상품 ID가 누락된 요청은 400 에러를 반환한다")
	void createInspection_fail_invalidProductId() throws Exception {
		// given
		ProductInspectionRequestDto invalidDto = new ProductInspectionRequestDto(
			null, // @NotNull 위반
			InspectionStatus.APPROVED,
			ProductCondition.MISB,
			"검수 통과"
		);

		// when & then
		mockMvc.perform(post("/api/v1/products/inspections")
				.with(csrf())
				.with(authentication(createAuth(PUBLIC_ID, "ADMIN")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(invalidDto)))
			.andExpect(status().isBadRequest())
			.andDo(print());
	}

	@Test
	@DisplayName("성공 - 특정 상품의 검수 내역 조회 시 200 OK와 상세 정보를 반환한다")
	void readInspection_Success() throws Exception {
		// given
		ProductInspectionResponseDto responseDto = ProductInspectionResponseDto.builder()
			.inspectionId(500L)
			.productId(PRODUCT_ID)
			.newStatus(InspectionStatus.APPROVED)
			.productCondition(ProductCondition.MISB)
			.reason("검수 완료")
			.createdAt(LocalDateTime.now())
			.build();

		given(productFacade.readInspection(PRODUCT_ID)).willReturn(responseDto);

		// when & then
		mockMvc.perform(get("/api/v1/products/inspections/{productId}", PRODUCT_ID)
				.with(authentication(createAuth(PUBLIC_ID, "SELLER")))
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.inspectionId").value(500L))
			.andExpect(jsonPath("$.data.newStatus").value("APPROVED"))
			.andExpect(jsonPath("$.data.reason").value("검수 완료"))
			.andDo(print());
	}

	@Test
	@DisplayName("실패 - 해당 상품의 검수 내역이 없으면 404 Not Found를 반환한다")
	void readInspection_Fail_NotFound() throws Exception {
		// given
		given(productFacade.readInspection(anyLong()))
			.willThrow(new CustomException(ErrorType.INSPECTION_NOT_FOUND));

		// when & then
		mockMvc.perform(get("/api/v1/products/inspections/{productId}", PRODUCT_ID)
				.with(authentication(createAuth(PUBLIC_ID, "SELLER")))
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isNotFound()) // Aspect 적용 확인
			.andDo(print());
	}

	@Test
	@DisplayName("관리자 상품 목록 조회 - 쿼리 파라미터가 DTO 및 Pageable로 잘 매핑되어야 한다")
	void getAdminProducts_Success() throws Exception {
		// given
		ProductResponseForInspectionDto productDto = new ProductResponseForInspectionDto(
			1L, "레고 스타워즈", "seller@test.com", Category.STARWARS, InspectionStatus.PENDING, "url0");

		Page<ProductResponseForInspectionDto> pageResponse = new PageImpl<>(List.of(productDto));
		PagedResponseDto<ProductResponseForInspectionDto> pagedResponse = PagedResponseDto.from(pageResponse);

		given(productFacade.readProductsForInspection(any(), any(Pageable.class)))
			.willReturn(pagedResponse);

		// when & then
		mockMvc.perform(get("/api/v1/products/inspections")
				.with(authentication(createAuth(PUBLIC_ID, "ADMIN")))
				.param("name", "레고")
				.param("category", "STARWARS")
				.param("status", "PENDING")
				.param("page", "0")
				.param("size", "10")
				.param("sort", "createdAt,desc")
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.data[0].name").value("레고 스타워즈"))
			.andDo(print());
	}
}