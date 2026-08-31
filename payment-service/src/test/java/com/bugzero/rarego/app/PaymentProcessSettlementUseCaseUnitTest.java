package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.SettlementType;
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

@ExtendWith(MockitoExtension.class)
class PaymentProcessSettlementUseCaseUnitTest {

	@InjectMocks
	private PaymentProcessSettlementUseCase useCase;

	@Mock
	private PaymentSettlementProcessor paymentSettlementProcessor;

	@Mock
	private OutboxUseCase outboxUseCase;

	@Test
	@DisplayName("정상 흐름: 여러 판매자의 정산 건이 섞여있을 때 판매자별로 그룹화하여 처리하고 Outbox를 저장한다")
	void processSettlements_success() {
		// given
		// 판매자 100L: 2건, 판매자 200L: 1건
		Settlement s1 = createMockSettlement(1L, 100L);
		Settlement s2 = createMockSettlement(2L, 100L);
		Settlement s3 = createMockSettlement(3L, 200L);
		List<Settlement> settlements = List.of(s1, s2, s3);

		// when
		useCase.processSettlements(settlements);

		// then
		// 1. 판매자별 그룹화 처리 검증 (100L은 2건 리스트, 200L은 1건 리스트로 전달되어야 함)
		verify(paymentSettlementProcessor).processRecipientDeposits(eq(100L), argThat(list -> list.size() == 2));
		verify(paymentSettlementProcessor).processRecipientDeposits(eq(200L), argThat(list -> list.size() == 1));

		// 2. 전체 결과에 대한 Outbox 저장 검증
		ArgumentCaptor<SettlementFinishedEvent> eventCaptor = ArgumentCaptor.forClass(SettlementFinishedEvent.class);
		verify(outboxUseCase).saveOutbox(eventCaptor.capture());

		SettlementFinishedEvent event = eventCaptor.getValue();
		assertThat(event.settlements()).hasSize(3); // 총 3건의 결과 포함 여부
	}

	@Test
	@DisplayName("예외 흐름: 프로세서 처리 중 예외 발생 시 UseCase 트랜잭션에 의해 상위로 전파된다 (Rollback 유도)")
	void processSettlements_throws_exception() {
		// given
		Settlement s1 = createMockSettlement(1L, 100L);
		List<Settlement> settlements = List.of(s1);

		doThrow(new RuntimeException("DB Error"))
			.when(paymentSettlementProcessor).processRecipientDeposits(anyLong(), anyList());

		// when & then
		assertThatThrownBy(() -> useCase.processSettlements(settlements))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("DB Error");

		// 예외 발생 시 이후 로직(Outbox 저장 등)이 실행되지 않아야 함
		verify(outboxUseCase, never()).saveOutbox(any());
	}

	@Test
	@DisplayName("빈 데이터: 처리할 리스트가 비어있으면 아무 작업도 하지 않는다")
	void processSettlements_empty() {
		// when
		useCase.processSettlements(Collections.emptyList());
		useCase.processSettlements(null);

		// then
		verifyNoInteractions(paymentSettlementProcessor);
		verifyNoInteractions(outboxUseCase);
	}

	@Test
	void splitSourcesGoToTheirRecipientsButOnlySellerReceivesNotification() {
		PaymentMember seller = PaymentMember.builder().id(100L).build();
		PaymentMember system = PaymentMember.builder().id(2L).build();
		List<Settlement> sources = Settlement.createPaymentSources(1L, "상품", seller, system, 100000);

		useCase.processSettlements(sources);

		var ordered = inOrder(paymentSettlementProcessor);
		ordered.verify(paymentSettlementProcessor).processRecipientDeposits(2L, List.of(sources.getLast()));
		ordered.verify(paymentSettlementProcessor).processRecipientDeposits(100L, List.of(sources.getFirst()));
		ArgumentCaptor<SettlementFinishedEvent> captor = ArgumentCaptor.forClass(SettlementFinishedEvent.class);
		verify(outboxUseCase).saveOutbox(captor.capture());
		assertThat(captor.getValue().settlements()).singleElement().satisfies(dto -> {
			assertThat(dto.sellerId()).isEqualTo(100L);
			assertThat(dto.settlementAmount()).isEqualTo(90000);
		});
	}

	@Test
	void feeOnlyChunkDoesNotPublishEmptySellerEvent() {
		PaymentMember seller = PaymentMember.builder().id(100L).build();
		PaymentMember system = PaymentMember.builder().id(2L).build();
		Settlement fee = Settlement.createPaymentSources(1L, "상품", seller, system, 100000).getLast();

		useCase.processSettlements(List.of(fee));

		verify(paymentSettlementProcessor).processRecipientDeposits(2L, List.of(fee));
		verifyNoInteractions(outboxUseCase);
	}

	private Settlement createMockSettlement(Long id, Long sellerId) {
		Settlement settlement = mock(Settlement.class);
		PaymentMember seller = mock(PaymentMember.class);

		lenient().when(settlement.getId()).thenReturn(id);
		lenient().when(seller.getId()).thenReturn(sellerId);
		lenient().when(settlement.getSeller()).thenReturn(seller);
		lenient().when(settlement.getRecipient()).thenReturn(seller);
		lenient().when(settlement.getType()).thenReturn(SettlementType.SELLER_PROCEEDS);

		lenient().when(settlement.getAuctionId()).thenReturn(id * 100);
		lenient().when(settlement.getSalesAmount()).thenReturn(10000);
		lenient().when(settlement.getFeeAmount()).thenReturn(1000);
		lenient().when(settlement.getSettlementAmount()).thenReturn(9000);
		lenient().when(settlement.getProductName()).thenReturn("테스트 상품 " + id);
		lenient().when(settlement.getStatus()).thenReturn(SettlementStatus.READY);
		lenient().when(settlement.getCreatedAt()).thenReturn(LocalDateTime.now());

		return settlement;
	}
}
