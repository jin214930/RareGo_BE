package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementPayout;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class PaymentProcessSettlementUseCaseUnitTest {
	@InjectMocks
	private PaymentProcessSettlementUseCase useCase;
	@Mock
	private SettlementRepository settlementRepository;
	@Mock
	private SettlementPayoutRepository payoutRepository;
	@Mock
	private EntityManager entityManager;

	@Test
	void preparesOnlyClaimedSourcesAndCombinesSameRecipientWithinChunk() {
		PaymentMember seller = PaymentMember.builder().id(100L).build();
		Settlement first = Settlement.create(1L, "상품1", seller, 10000);
		Settlement second = Settlement.create(2L, "상품2", seller, 20000);
		ReflectionTestUtils.setField(first, "id", 1L);
		ReflectionTestUtils.setField(second, "id", 2L);
		given(settlementRepository.findReadyForUpdate(List.of(1L, 2L, 3L)))
			.willReturn(List.of(first, second));
		given(payoutRepository.save(any(SettlementPayout.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(settlementRepository.assignPayout(eq(List.of(1L, 2L)), any())).willReturn(2);

		useCase.prepareSettlements(10L, List.of(3L, 2L, 1L, 1L));

		verify(payoutRepository).save(argThat(p -> p.getRunId() == 10L && p.getRecipientId() == 100L
			&& p.getChunkId() == 1L && p.getAmount() == 27000 && p.getSourceCount() == 2 && !p.isPaid()));
		verify(entityManager).flush();
		verify(entityManager).clear();
	}

	@Test
	void emptySourcesDoNothingAndMissingRunIsRejected() {
		useCase.prepareSettlements(10L, List.of());
		useCase.prepareSettlements(10L, null);
		assertThatIllegalArgumentException().isThrownBy(() -> useCase.prepareSettlements(null, List.of(1L)));
		verifyNoInteractions(settlementRepository, payoutRepository, entityManager);
	}
}
