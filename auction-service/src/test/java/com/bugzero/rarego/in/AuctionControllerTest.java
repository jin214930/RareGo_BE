package com.bugzero.rarego.in;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.argThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bugzero.rarego.app.AuctionFacade;
import com.bugzero.rarego.domain.AuctionOrderStatus;
import com.bugzero.rarego.global.aspect.ResponseAspect;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.global.response.PageDto;
import com.bugzero.rarego.global.response.PagedResponseDto;
import com.bugzero.rarego.global.response.SuccessResponseDto;
import com.bugzero.rarego.global.response.SuccessType;
import com.bugzero.rarego.global.security.MemberPrincipal;
import com.bugzero.rarego.in.dto.AuctionAddBookmarkResponseDto;
import com.bugzero.rarego.in.dto.AuctionDetailResponseDto;
import com.bugzero.rarego.in.dto.AuctionOrderResponseDto;
import com.bugzero.rarego.in.dto.AuctionRelistRequestDto;
import com.bugzero.rarego.in.dto.AuctionRelistResponseDto;
import com.bugzero.rarego.in.dto.AuctionRemoveBookmarkResponseDto;
import com.bugzero.rarego.in.dto.BidLogResponseDto;
import com.bugzero.rarego.in.dto.BidRequestDto;
import com.bugzero.rarego.in.dto.BidResponseDto;
import com.bugzero.rarego.shared.auction.dto.AuctionSortType;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.type.Category;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AuctionController.class)
@Import(ResponseAspect.class)
@EnableAspectJAutoProxy
class AuctionControllerTest {
	@Autowired
	private MockMvc mockMvc;

	@InjectMocks
	private AuctionController auctionController;

	@MockitoBean
	private AuctionFacade auctionFacade;

	private ObjectMapper objectMapper = new ObjectMapper();

	private Authentication createAuth(String publicId, String role) {
		MemberPrincipal principal = new MemberPrincipal(publicId, role);

		return new UsernamePasswordAuthenticationToken(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role))
		);
	}

	@Test
	@DisplayName("POST /auctions/{id}/bids - 입찰 생성 성공")
	void createBid_success() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "user-1";
		Long bidAmount = 10000L;
		BidRequestDto requestDto = new BidRequestDto(bidAmount);

		BidResponseDto bidResponse = new BidResponseDto(
			100L, auctionId, memberPublicId, LocalDateTime.now(), bidAmount, 11000L);

		SuccessResponseDto<BidResponseDto> successResponse = SuccessResponseDto.from(
			SuccessType.CREATED,
			bidResponse);

		given(auctionFacade.createBid(eq(auctionId), eq(memberPublicId), eq(bidAmount.intValue())))
			.willReturn(successResponse);

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
				.with(csrf()) // CSRF 보호 우회
				.with(authentication(createAuth(memberPublicId, "USER"))) // 인증 주입
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(requestDto)))
			.andDo(print())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(SuccessType.CREATED.getHttpStatus()))
			.andExpect(jsonPath("$.data.bidAmount").value(bidAmount));
	}

	@Test
	@DisplayName("GET /auctions/{id}/bids - 경매 입찰 기록 조회 성공")
	void getBids_success() throws Exception {
		// given
		Long auctionId = 1L;
		BidLogResponseDto logDto = new BidLogResponseDto(
			10L, "user_***", LocalDateTime.now(), 50000);

		PagedResponseDto<BidLogResponseDto> response = new PagedResponseDto<>(
			List.of(logDto), new PageDto(1, 10, 1, 1, false, false));

		given(auctionFacade.getBidLogs(eq(auctionId), any(Pageable.class)))
			.willReturn(response);

		// when & then
		mockMvc.perform(get("/api/v1/auctions/{auctionId}/bids", auctionId)
				.with(csrf())
				.with(authentication(createAuth("user-1", "USER")))
				.param("page", "0")
				.param("size", "10"))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].publicId").value("user_***"))
			.andExpect(jsonPath("$.data[0].bidAmount").value(50000));
	}

	@Test
	@DisplayName("실패: 유효성 검사 실패 시 400 에러코드 반환")
	void createBid_fail_validation() throws Exception {
		// given
		Long auctionId = 1L;
		BidRequestDto invalidRequest = new BidRequestDto(-500L); // 음수 금액 불가

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
				.with(csrf())
				.with(authentication(createAuth("user-1", "USER")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(invalidRequest)))
			.andDo(print())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	@DisplayName("실패: 비즈니스 예외 발생 시 404 에러코드 반환")
	void createBid_fail_business_exception() throws Exception {
		// given
		Long auctionId = 999L;
		String memberPublicId = "user-1";
		Long bidAmount = 10000L;
		BidRequestDto requestDto = new BidRequestDto(bidAmount);

		given(auctionFacade.createBid(eq(auctionId), eq(memberPublicId), eq(bidAmount.intValue())))
			.willThrow(new CustomException(ErrorType.AUCTION_NOT_FOUND));

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/bids", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(requestDto)))
			.andDo(print())
			.andExpect(status().isNotFound()) // Aspect 덕분에 200이 아닌 404 반환
			.andExpect(jsonPath("$.status").value(ErrorType.AUCTION_NOT_FOUND.getHttpStatus()));
	}

	@Test
	@DisplayName("경매 상세 조회 성공")
	void getAuctionDetail_success() throws Exception {
		// given
		Long auctionId = 100L;
		String memberPublicId = "user-1";

		AuctionDetailResponseDto responseDto = new AuctionDetailResponseDto(
			auctionId,
			50L,
			"Lego Product",
			"Description",
			List.of("thumbnail.jpg"),
			AuctionStatus.IN_PROGRESS,
			LocalDateTime.now(),
			LocalDateTime.now().plusDays(1),
			3600L,
			new AuctionDetailResponseDto.PriceInfo(10000, 20000, 1000),
			new AuctionDetailResponseDto.BidInfo(true, 21000, null, false, false),
			new AuctionDetailResponseDto.MyParticipationInfo(false, null));

		given(auctionFacade.getAuctionDetail(eq(auctionId), eq(memberPublicId)))
			.willReturn(SuccessResponseDto.from(SuccessType.OK, responseDto));

		// when & then
		mockMvc.perform(get("/api/v1/auctions/{auctionId}", auctionId)
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.auctionId").value(auctionId))
			.andExpect(jsonPath("$.data.price.currentPrice").value(20000))
			.andExpect(jsonPath("$.data.bid.canBid").value(true));
	}

	@Test
	@DisplayName("낙찰 기록(주문) 상세 조회 성공 - 인증된 사용자")
	void getAuctionOrder_success() throws Exception {
		// given
		Long auctionId = 100L;
		String memberPublicId = "user-1";

		AuctionOrderResponseDto responseDto = new AuctionOrderResponseDto(
			7001L, auctionId, "BUYER", AuctionOrderStatus.PROCESSING, "결제 대기중",
			LocalDateTime.now(),
			new AuctionOrderResponseDto.ProductInfo("Lego Titanic", "img.jpg"),
			new AuctionOrderResponseDto.PaymentInfo(150000, 15000, 135000),
			new AuctionOrderResponseDto.TraderInfo("SellerNick", "010-1234-5678"),
			new AuctionOrderResponseDto.ShippingInfo(null, null, null));

		given(auctionFacade.getAuctionOrder(eq(auctionId), eq(memberPublicId)))
			.willReturn(SuccessResponseDto.from(SuccessType.OK, responseDto));

		// when & then
		mockMvc.perform(get("/api/v1/auctions/{auctionId}/order", auctionId)
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.orderId").value(7001L))
			.andExpect(jsonPath("$.data.viewerRole").value("BUYER"));
	}

	@Test
	@DisplayName("GET /auctions - 경매 목록 검색 (조건 매핑 확인)")
	void getAuctions_success() throws Exception {
		// given

		// when
		mockMvc.perform(get("/api/v1/auctions")
				.with(csrf())
				.with(authentication(createAuth("user-1", "USER")))
				.param("keyword", "Lego")
				.param("category", "STARWARS")
				.param("sort", "CLOSING_SOON"))
			.andExpect(status().isOk());

		// then: 파라미터가 Condition 객체로 잘 변환되어 Facade로 전달되었는지 검증
		verify(auctionFacade).getAuctions(
			argThat(condition ->
				"Lego".equals(condition.getKeyword()) &&
					Category.STARWARS == condition.getCategory() &&
					AuctionSortType.CLOSING_SOON == condition.getSort()
			),
			any(Pageable.class)
		);
	}

	@Test
	@DisplayName("성공: 관심 경매 등록 시 HTTP 200과 등록 정보를 반환한다")
	void addBookmark_success() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "test-public-id";
		AuctionAddBookmarkResponseDto responseDto = AuctionAddBookmarkResponseDto.of(true, auctionId);

		given(auctionFacade.addBookmark(eq(memberPublicId), eq(auctionId)))
			.willReturn(responseDto);

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/bookmarks", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.bookmarked").value(true));
	}

	@Test
	@DisplayName("성공: 이미 관심 등록된 경매에 중복 등록 시 bookmarked=false를 반환한다")
	void addBookmark_already_exists() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "test-public-id";
		AuctionAddBookmarkResponseDto responseDto = AuctionAddBookmarkResponseDto.of(false, auctionId);

		given(auctionFacade.addBookmark(eq(memberPublicId), eq(auctionId)))
			.willReturn(responseDto);

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/bookmarks", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.bookmarked").value(false));
	}

	@Test
	@DisplayName("실패: 존재하지 않는 경매에 관심 등록 시 404를 반환한다")
	void addBookmark_fail_auction_not_found() throws Exception {
		// given
		Long auctionId = 999L;
		String memberPublicId = "test-public-id";

		given(auctionFacade.addBookmark(eq(memberPublicId), eq(auctionId)))
			.willThrow(new CustomException(ErrorType.AUCTION_NOT_FOUND));

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/bookmarks", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isNotFound()) // Aspect 적용 확인
			.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	@DisplayName("성공: 관심 경매 해제 시 HTTP 200과 해제 정보를 반환한다")
	void removeBookmark_success() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "test-public-id";
		AuctionRemoveBookmarkResponseDto responseDto = AuctionRemoveBookmarkResponseDto.of(true, auctionId);

		given(auctionFacade.removeBookmark(eq(memberPublicId), eq(auctionId)))
			.willReturn(responseDto);

		// when & then
		mockMvc.perform(delete("/api/v1/auctions/{auctionId}/bookmarks", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.removed").value(true));
	}

	@Test
	@DisplayName("실패: 관심 등록되지 않은 경매 해제 시 404를 반환한다")
	void removeBookmark_fail_bookmark_not_found() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "test-public-id";

		given(auctionFacade.removeBookmark(eq(memberPublicId), eq(auctionId)))
			.willThrow(new CustomException(ErrorType.BOOKMARK_NOT_FOUND));

		// when & then
		mockMvc.perform(delete("/api/v1/auctions/{auctionId}/bookmarks", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	@DisplayName("실패: 타인의 북마크를 해제하려 할 때 403을 반환한다")
	void removeBookmark_fail_unauthorized() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "test-public-id";

		given(auctionFacade.removeBookmark(eq(memberPublicId), eq(auctionId)))
			.willThrow(new CustomException(ErrorType.BOOKMARK_UNAUTHORIZED_ACCESS));

		// when & then
		mockMvc.perform(delete("/api/v1/auctions/{auctionId}/bookmarks", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isForbidden()) // 403 Forbidden
			.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	@DisplayName("POST /auctions/{id}/relist - 재경매 등록 성공")
	void relistAuction_success() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "user-1";
		AuctionRelistRequestDto request = new AuctionRelistRequestDto(20000L, 1000L, 7);

		AuctionRelistResponseDto responseDto = AuctionRelistResponseDto.builder()
			.newAuctionId(2L)
			.productId(50L)
			.status(AuctionStatus.SCHEDULED)
			.message("성공")
			.build();

		given(auctionFacade.relistAuction(eq(auctionId), eq(memberPublicId), any(AuctionRelistRequestDto.class)))
			.willReturn(SuccessResponseDto.from(SuccessType.OK, responseDto));

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/relist", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.newAuctionId").value(2L))
			.andExpect(jsonPath("$.data.status").value("SCHEDULED"));
	}

	@Test
	@DisplayName("실패: 이미 판매된 경매 재등록 시 409 Conflict 반환")
	void relistAuction_fail_conflict() throws Exception {
		// given
		Long auctionId = 1L;
		String memberPublicId = "user-1";
		AuctionRelistRequestDto request = new AuctionRelistRequestDto(20000L, 1000L, 7);

		given(auctionFacade.relistAuction(eq(auctionId), eq(memberPublicId), any(AuctionRelistRequestDto.class)))
			.willThrow(new CustomException(ErrorType.AUCTION_ALREADY_SOLD));

		// when & then
		mockMvc.perform(post("/api/v1/auctions/{auctionId}/relist", auctionId)
				.with(csrf())
				.with(authentication(createAuth(memberPublicId, "USER")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andDo(print())
			.andExpect(status().isConflict()) // 409
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.message").value(ErrorType.AUCTION_ALREADY_SOLD.getMessage()));
	}

	@Test
	@DisplayName("성공 - 시작 시간 확정 요청 시 200 OK와 경매 ID를 반환한다")
	void determineStartAuction_Success() throws Exception {
		// given
		Long productId = 1L;

		given(auctionFacade.determineStartAuction(eq(productId)))
			.willReturn(productId);

		// when & then
		mockMvc.perform(patch("/api/v1/auctions/{productId}/startTime", productId)
				.with(csrf()) // Patch 메서드도 CSRF 필요
				.with(authentication(createAuth("admin", "ADMIN"))) // 보통 이런 기능은 관리자나 시스템이 호출
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data").value(productId))
			.andDo(print());
	}

}
