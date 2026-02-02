package com.bugzero.rarego.boundedContext.auction.app;

import com.bugzero.rarego.boundedContext.auction.domain.Auction;
import com.bugzero.rarego.boundedContext.auction.domain.AuctionStatus;
import com.bugzero.rarego.boundedContext.auction.out.AuctionRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AuctionSettleOneUseCaseTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionSettlementSupport support;

    @InjectMocks
    private AuctionSettleOneUseCase useCase;

    /**
     * 테스트용 경매 객체 생성 헬퍼 메서드
     */
    private Auction createAuction(Long id, AuctionStatus status, LocalDateTime endTime) {
        Auction auction = Auction.builder()
                .productId(100L)
                .sellerId(1L)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(endTime)
                .startPrice(10_000)
                .durationDays(1) // 엔티티 생성자/빌더 NPE 방지를 위해 필수값 추가
                .build();

        ReflectionTestUtils.setField(auction, "id", id);
        ReflectionTestUtils.setField(auction, "status", status);

        return auction;
    }

    @Test
    @DisplayName("경매가 존재하지 않으면 예외 발생")
    void execute_AuctionNotFound() {
        // given
        given(auctionRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> useCase.execute(1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.AUCTION_NOT_FOUND);

        // 정산 로직이 실행되지 않았음을 확인
        verify(support, never()).processSettlement(any());
    }

    @Test
    @DisplayName("이미 종료된(ENDED) 경매는 아무런 처리를 하지 않고 리턴한다")
    void execute_AlreadyEnded() {
        // given: 이미 종료된 상태의 경매
        Auction auction = createAuction(1L, AuctionStatus.ENDED, LocalDateTime.now().minusHours(1));
        given(auctionRepository.findById(1L)).willReturn(Optional.of(auction));

        // when
        useCase.execute(1L);

        // then: support 호출이 절대 일어나지 않아야 함 (Early Return 확인)
        verify(support, never()).processSettlement(any());
    }

    @Test
    @DisplayName("진행 중(IN_PROGRESS)이 아닌 경매는 예외 발생")
    void execute_NotInProgress() {
        // given: 대기 중(SCHEDULED) 상태의 경매
        Auction auction = createAuction(1L, AuctionStatus.SCHEDULED, LocalDateTime.now().minusHours(1));
        given(auctionRepository.findById(1L)).willReturn(Optional.of(auction));

        // when & then
        assertThatThrownBy(() -> useCase.execute(1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.AUCTION_NOT_IN_PROGRESS);

        verify(support, never()).processSettlement(any());
    }

    @Test
    @DisplayName("종료 시간(endTime)이 아직 지나지 않은 경매는 예외 발생")
    void execute_NotExpiredYet() {
        // given: 진행 중이지만 종료 시간이 미래인 경매
        Auction auction = createAuction(1L, AuctionStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));
        given(auctionRepository.findById(1L)).willReturn(Optional.of(auction));

        // when & then
        assertThatThrownBy(() -> useCase.execute(1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.AUCTION_NOT_IN_PROGRESS);

        verify(support, never()).processSettlement(any());
    }

    @Test
    @DisplayName("검증을 모두 통과하면 Support를 통해 정산 처리를 수행한다")
    void execute_Success() {
        // given: 정산 가능한 조건의 경매
        Auction auction = createAuction(1L, AuctionStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(1));
        given(auctionRepository.findById(1L)).willReturn(Optional.of(auction));

        // when
        useCase.execute(1L);

        // then: 정산 핵심 로직이 1번 호출되었는지 확인
        verify(support, times(1)).processSettlement(auction);
    }
}