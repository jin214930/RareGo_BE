package com.bugzero.rarego.in;

import static org.mockito.ArgumentMatchers.*;
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
import org.springframework.data.domain.Pageable;
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
import com.bugzero.rarego.global.security.MemberPrincipal;
import com.bugzero.rarego.in.dto.AuctionBookmarkListResponseDto;
import com.bugzero.rarego.in.dto.AuctionListResponseDto;
import com.bugzero.rarego.in.dto.MyAuctionOrderListResponseDto;
import com.bugzero.rarego.in.dto.MyBidResponseDto;
import com.bugzero.rarego.in.dto.MySaleResponseDto;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;

@WebMvcTest(AuctionMemberController.class)
@Import(ResponseAspect.class)
@EnableAspectJAutoProxy
class AuctionMemberControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuctionFacade auctionFacade;

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
	@DisplayName("성공: 내 입찰 목록을 조회하면 페이징 결과를 반환한다")
	void getMyBids_success() throws Exception {
		// given
		String memberPublicId = "user-2";

		// [수정] DTO Record 구조에 맞춰 순서대로 값 주입
		MyBidResponseDto bid = new MyBidResponseDto(
			1L,                                  // bidId
			20L,                                 // auctionId
			30L,                                 // productId
			4000L,                               // bidAmount
			LocalDateTime.of(2024, 1, 1, 12, 0), // bidTime (추가됨)
			AuctionStatus.IN_PROGRESS,           // auctionStatus
			5000L,                               // currentPrice
			LocalDateTime.of(2024, 1, 2, 10, 0)  // endTime
		);

		PagedResponseDto<MyBidResponseDto> response = new PagedResponseDto<>(
			List.of(bid),
			new PageDto(1, 20, 1, 1, false, false)
		);

		given(auctionFacade.getMyBids(eq(memberPublicId), eq(AuctionStatus.IN_PROGRESS), any(Pageable.class)))
			.willReturn(response);

		// when & then
		mockMvc.perform(get("/api/v1/members/me/bids")
				.param("auctionStatus", "IN_PROGRESS")
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].bidId").value(1L))
			.andExpect(jsonPath("$.data[0].auctionId").value(20L))
			.andExpect(jsonPath("$.data[0].bidAmount").value(4000L))
			.andExpect(jsonPath("$.pageDto.totalItems").value(1));
	}

	@Test
	@DisplayName("성공: 내 낙찰(주문) 목록 조회 - 상태 필터링 포함")
	void getMyAuctionOrders_success() throws Exception {
		// given
		String memberPublicId = "user-2";

		// [수정] DTO Record 구조에 맞춰 순서대로 값 주입
		MyAuctionOrderListResponseDto orderDto = new MyAuctionOrderListResponseDto(
			1001L,                        // orderId
			1L,                           // auctionId
			"Lego Titanic",               // productName
			"thumb.jpg",                  // thumbnailUrl
			850000,                       // finalPrice
			AuctionOrderStatus.PROCESSING,// orderStatus
			"결제 대기중",                 // statusDescription
			LocalDateTime.now(),          // tradeDate
			true                          // auctionRequired
		);

		PagedResponseDto<MyAuctionOrderListResponseDto> response = new PagedResponseDto<>(
			List.of(orderDto),
			new PageDto(1, 10, 1, 1, false, false)
		);

		given(auctionFacade.getMyAuctionOrders(eq(memberPublicId), eq(AuctionOrderStatus.PROCESSING),
			any(Pageable.class)))
			.willReturn(response);

		// when & then
		mockMvc.perform(get("/api/v1/members/me/orders")
				.param("status", "PROCESSING")
				.param("page", "0")
				.param("size", "10")
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].orderId").value(1001L))
			.andExpect(jsonPath("$.data[0].productName").value("Lego Titanic"))
			.andExpect(jsonPath("$.data[0].statusDescription").value("결제 대기중"))
			.andExpect(jsonPath("$.data[0].auctionRequired").value(true));
	}

	@Test
	@DisplayName("성공: 내 관심 경매 목록 조회 시 200 OK와 중첩된 경매 정보를 반환한다")
	void getMyBookmarks_success() throws Exception {
		// given
		String memberPublicId = "user-2";

		AuctionListResponseDto auctionInfo = new AuctionListResponseDto(
			100L,
			500L,
			"레고 밀레니엄 팔콘",
			"https://image.com/1",
			"",
			850000,
			800000,
			15,
			AuctionStatus.IN_PROGRESS,
			LocalDateTime.of(2026, 1, 30, 23, 59)
		);

		AuctionBookmarkListResponseDto bookmarkResponse = AuctionBookmarkListResponseDto.of(
			1L,
			auctionInfo
		);

		PagedResponseDto<AuctionBookmarkListResponseDto> response = new PagedResponseDto<>(
			List.of(bookmarkResponse),
			new PageDto(1, 10, 1, 1, false, false)
		);

		given(auctionFacade.getMyBookmarks(eq(memberPublicId), any(Pageable.class)))
			.willReturn(response);

		// when & then
		mockMvc.perform(get("/api/v1/members/me/bookmarks")
				.param("page", "0")
				.param("size", "10")
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].bookmarkId").value(1L))
			.andExpect(jsonPath("$.data[0].auctionInfo.productName").value("레고 밀레니엄 팔콘"));
	}

	@Test
	@DisplayName("성공: 관심 경매가 없을 때 빈 목록을 반환한다")
	void getMyBookmarks_emptyList() throws Exception {
		// given
		String memberPublicId = "user-2";
		PagedResponseDto<AuctionBookmarkListResponseDto> response = new PagedResponseDto<>(
			List.of(),
			new PageDto(1, 10, 0, 0, false, false)
		);

		given(auctionFacade.getMyBookmarks(eq(memberPublicId), any(Pageable.class)))
			.willReturn(response);

		// when & then
		mockMvc.perform(get("/api/v1/members/me/bookmarks")
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	@DisplayName("실패: 회원을 찾을 수 없는 경우 404를 반환한다")
	void getMyBookmarks_memberNotFound() throws Exception {
		// given
		String memberPublicId = "unknown";

		given(auctionFacade.getMyBookmarks(eq(memberPublicId), any(Pageable.class)))
			.willThrow(new CustomException(ErrorType.MEMBER_NOT_FOUND));

		// when & then
		mockMvc.perform(get("/api/v1/members/me/bookmarks")
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	@DisplayName("성공: 내 판매 목록 조회")
	void getMySales_success() throws Exception {
		// given
		String memberPublicId = "user-2";

		// MySaleResponseDto 생성자는 기존 코드를 참고하여 작성 (필요시 수정)
		MySaleResponseDto saleDto = new MySaleResponseDto(
			100L, 100L,"판매 상품", "thumb.jpg", 10000, 5, AuctionStatus.IN_PROGRESS, AuctionOrderStatus.SUCCESS,
			LocalDateTime.now(), false, null
		);

		PagedResponseDto<MySaleResponseDto> response = new PagedResponseDto<>(
			List.of(saleDto), new PageDto(1, 10, 1, 1, false, false)
		);

		given(auctionFacade.getMySales(eq(memberPublicId), any(), any(Pageable.class)))
			.willReturn(response);

		// when & then
		mockMvc.perform(get("/api/v1/members/me/sales")
				.with(authentication(createAuth(memberPublicId, "USER"))))
			.andDo(print())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].auctionId").value(100L))
			.andExpect(jsonPath("$.data[0].bidCount").value(5));
	}
}
