package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.out.SettlementPayoutRepository;
import com.bugzero.rarego.out.SettlementRepository;

@ExtendWith(MockitoExtension.class)
class PaymentProcessSettlementUseCaseUnitTest {
	@InjectMocks
	private PaymentProcessSettlementUseCase useCase;
	@Mock
	private SettlementRepository settlementRepository;
	@Mock
	private SettlementPayoutRepository payoutRepository;

	@Test
	void preparesOnlySourcesWhoseStateTransitionSucceeded() {
		PaymentMember seller = PaymentMember.builder().id(100L).build();
		Settlement source = Settlement.create(1L, "상품", seller, 10000);
		ReflectionTestUtils.setField(source, "status", SettlementStatus.PENDING);
		given(settlementRepository.markPendingIfReady(1L)).willReturn(1);
		given(settlementRepository.findById(1L)).willReturn(Optional.of(source));

		useCase.prepareSettlements(10L, List.of(2L, 1L, 1L));

		verify(payoutRepository).save(argThat(p -> p.getRunId() == 10L && p.getRecipientId() == 100L
			&& p.getAmount() == 9000 && !p.isPaid()));
		verify(settlementRepository, never()).findById(2L);
		verify(settlementRepository, never()).findByIdForUpdate(anyLong());
		assertThat(source.getStatus()).isEqualTo(SettlementStatus.PENDING);
	}

	@Test
	void emptySourcesDoNothingAndMissingRunIsRejected() {
		useCase.prepareSettlements(10L, List.of());
		useCase.prepareSettlements(10L, null);
		assertThatIllegalArgumentException().isThrownBy(() -> useCase.prepareSettlements(null, List.of(1L)));
		verifyNoInteractions(settlementRepository, payoutRepository);
	}
}
