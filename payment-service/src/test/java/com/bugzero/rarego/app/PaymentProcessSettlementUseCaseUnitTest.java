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
import com.bugzero.rarego.global.outbox.app.OutboxUseCase;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.shared.payment.event.SettlementFinishedEvent;

@ExtendWith(MockitoExtension.class)
class PaymentProcessSettlementUseCaseUnitTest {

	@InjectMocks
	private PaymentProcessSettlementUseCase useCase;

	@Mock
	private PaymentSettlementProcessor paymentSettlementProcessor;

	@Mock
	private SettlementRepository settlementRepository;

	@Mock
	private OutboxUseCase outboxUseCase;

	@Test
	@DisplayName("정상 흐름: 2건 모두 성공 시 - 판매자 처리 2회 후 결과가 담긴 Outbox 저장")
	void success_all() {
		// given
		Settlement s1 = createMockSettlement(1L);
		Settlement s2 = createMockSettlement(2L);
		List<Settlement> list = List.of(s1, s2);

		given(settlementRepository.findSettlementsForBatch(eq(SettlementStatus.READY), any(), anyInt()))
			.willReturn(list);

		given(paymentSettlementProcessor.processSellerDeposit(s1)).willReturn(true);
		given(paymentSettlementProcessor.processSellerDeposit(s2)).willReturn(true);

		// when
		int count = useCase.processSettlements(10);

		// then
		assertThat(count).isEqualTo(2);

		verify(paymentSettlementProcessor).processSellerDeposit(s1);
		verify(paymentSettlementProcessor).processSellerDeposit(s2);

		// EventPublisher 대신 OutboxUseCase 호출 검증
		ArgumentCaptor<SettlementFinishedEvent> eventCaptor = ArgumentCaptor.forClass(SettlementFinishedEvent.class);
		verify(outboxUseCase).saveOutbox(eventCaptor.capture());

		SettlementFinishedEvent event = eventCaptor.getValue();
		assertThat(event.settlements()).hasSize(2);
		assertThat(event.settlements().get(0).id()).isEqualTo(1L);
		assertThat(event.settlements().get(1).id()).isEqualTo(2L);
	}

	@Test
	@DisplayName("동시성 방어: 프로세서가 false를 반환하면 카운트되지 않고 Outbox 저장도 발생하지 않음")
	void skip_if_processor_returns_false() {
		// given
		Settlement s1 = createMockSettlement(1L);
		given(settlementRepository.findSettlementsForBatch(any(), any(), anyInt()))
			.willReturn(List.of(s1));

		given(paymentSettlementProcessor.processSellerDeposit(s1)).willReturn(false);

		// when
		int count = useCase.processSettlements(10);

		// then
		assertThat(count).isEqualTo(0);

		// 성공한 정산이 없으므로 Outbox 저장 로직이 호출되지 않아야 함
		then(outboxUseCase).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("부분 성공: 1건 성공, 1건 실패(예외) 시 - 실패 처리 후 성공한 건만 Outbox에 저장됨")
	void partial_success() {
		// given
		Settlement successItem = createMockSettlement(1L);
		Settlement failItem = createMockSettlement(2L);

		given(settlementRepository.findSettlementsForBatch(any(), any(), anyInt()))
			.willReturn(List.of(successItem, failItem));

		given(paymentSettlementProcessor.processSellerDeposit(successItem)).willReturn(true);
		given(paymentSettlementProcessor.processSellerDeposit(failItem))
			.willThrow(new RuntimeException("Something wrong"));

		// when
		int count = useCase.processSettlements(10);

		// then
		assertThat(count).isEqualTo(1);

		verify(failItem).fail();

		// 성공한 1건에 대해서만 Outbox에 저장되는지 검증
		ArgumentCaptor<SettlementFinishedEvent> eventCaptor = ArgumentCaptor.forClass(SettlementFinishedEvent.class);
		verify(outboxUseCase).saveOutbox(eventCaptor.capture());

		assertThat(eventCaptor.getValue().settlements()).hasSize(1);
		assertThat(eventCaptor.getValue().settlements().get(0).id()).isEqualTo(1L);
	}

	@Test
	@DisplayName("빈 데이터: 데이터가 없으면 아무 작업도 하지 않고 0 반환")
	void empty_data_then_do_nothing() {
		// given
		given(settlementRepository.findSettlementsForBatch(any(), any(), anyInt()))
			.willReturn(Collections.emptyList());

		// when
		int count = useCase.processSettlements(10);

		// then
		assertThat(count).isEqualTo(0);

		then(paymentSettlementProcessor).shouldHaveNoInteractions();
		then(outboxUseCase).shouldHaveNoInteractions();
	}

	private Settlement createMockSettlement(Long id) {
		Settlement settlement = mock(Settlement.class);
		PaymentMember seller = mock(PaymentMember.class);

		lenient().when(settlement.getId()).thenReturn(id);

		lenient().when(seller.getId()).thenReturn(id * 10);
		lenient().when(settlement.getSeller()).thenReturn(seller);

		lenient().when(settlement.getAuctionId()).thenReturn(id * 100);
		lenient().when(settlement.getSalesAmount()).thenReturn(10000);
		lenient().when(settlement.getFeeAmount()).thenReturn(1000);
		lenient().when(settlement.getSettlementAmount()).thenReturn(9000);
		lenient().when(settlement.getStatus()).thenReturn(SettlementStatus.READY);
		// DTO 생성 시 발생하는 NPE 방지를 위해 CreatedAt 추가
		lenient().when(settlement.getCreatedAt()).thenReturn(LocalDateTime.now());

		return settlement;
	}
}
