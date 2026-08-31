package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.SettlementType;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.out.SettlementRepository;

@ExtendWith(MockitoExtension.class)
class PaymentCreateSettlementUseCaseTest {
	@InjectMocks
	private PaymentCreateSettlementUseCase useCase;
	@Mock
	private SettlementRepository repository;
	@Mock
	private PaymentSupport support;

	private final PaymentMember seller = PaymentMember.builder().id(10L).build();
	private final PaymentMember system = PaymentMember.builder().id(1L).build();

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(useCase, "systemMemberId", 1L);
	}

	@Test
	void normalPaymentMustNotDebitAgainWhenAnySourceAlreadyExists() {
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(paymentSources(100000));

		assertThatThrownBy(() -> useCase.validateNotCreated(100L)).isInstanceOf(CustomException.class);
		verify(repository, never()).save(any());
	}

	@Test
	void pendingSourcesAreRecognizedDuringPaymentRecovery() {
		List<Settlement> existing = paymentSources(100000);
		existing.forEach(source -> ReflectionTestUtils.setField(source, "status", SettlementStatus.PENDING));
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(existing);

		assertThat(useCase.hasCompletePaymentSources(100L, "레고", seller, 100000)).isTrue();
		verify(repository, never()).save(any());
	}

	@Test
	void createsBothSources() {
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));

		List<Settlement> result = useCase.createForPayment(100L, "레고", seller, 100000);

		assertThat(result).extracting(Settlement::getSettlementAmount).containsExactly(90000, 10000);
		verify(repository, times(2)).save(any(Settlement.class));
		verify(support, never()).findWalletByMemberIdForUpdate(anyLong());
	}

	@Test
	void repeatedRequestReusesBothSourcesWithoutSavingAgain() {
		List<Settlement> existing = paymentSources(100000);
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(existing);

		assertThat(useCase.createForPayment(100L, "레고", seller, 100000)).containsExactlyElementsOf(existing);
		assertThat(useCase.hasCompletePaymentSources(100L, "레고", seller, 100000)).isTrue();
		verify(repository, never()).save(any());
	}

	@Test
	void incompletePairIsNotCompleteAndOnlyMissingFeeIsSaved() {
		Settlement proceeds = paymentSources(100000).getFirst();
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(List.of(proceeds));
		given(repository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));

		assertThat(useCase.hasCompletePaymentSources(100L, "레고", seller, 100000)).isFalse();
		List<Settlement> result = useCase.createForPayment(100L, "레고", seller, 100000);

		assertThat(result.getFirst()).isSameAs(proceeds);
		verify(repository).save(argThat(source -> source.getType() == SettlementType.PLATFORM_FEE));
	}

	@Test
	void missingSellerSourceIsRestoredWithoutDuplicatingFee() {
		Settlement fee = paymentSources(100000).getLast();
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(List.of(fee));
		given(repository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));

		List<Settlement> result = useCase.createForPayment(100L, "레고", seller, 100000);

		assertThat(result.getLast()).isSameAs(fee);
		verify(repository).save(argThat(source -> source.getType() == SettlementType.SELLER_PROCEEDS));
	}

	@Test
	void conflictingAmountIsRejectedBeforeAnySave() {
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(paymentSources(200000));

		assertThatIllegalStateException().isThrownBy(() -> useCase.createForPayment(100L, "레고", seller, 100000));
		verify(repository, never()).save(any());
	}

	@Test
	void conflictingRecipientIsRejected() {
		PaymentMember wrongSystem = PaymentMember.builder().id(2L).build();
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.findAllByAuctionIdForUpdate(100L))
			.willReturn(Settlement.createPaymentSources(100L, "레고", seller, wrongSystem, 100000));

		assertThatIllegalStateException().isThrownBy(
			() -> useCase.hasCompletePaymentSources(100L, "레고", seller, 100000));
		verify(repository, never()).save(any());
	}

	@Test
	void canceledSourceIsNotRecreated() {
		List<Settlement> existing = paymentSources(100000);
		existing.getFirst().cancel();
		given(support.findMemberById(1L)).willReturn(system);
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(existing);

		assertThatIllegalStateException().isThrownBy(() -> useCase.createForPayment(100L, "레고", seller, 100000));
		verify(repository, never()).save(any());
	}

	@Test
	void forfeitCreatesOneSourceWithoutSystemLookupAndIsIdempotent() {
		given(repository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));
		Settlement source = useCase.createFromForfeit(100L, "레고", seller, 10000);
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(List.of(source));

		assertThat(useCase.createFromForfeit(100L, "레고", seller, 10000)).isSameAs(source);
		assertThat(source.getType()).isEqualTo(SettlementType.DEPOSIT_FORFEIT);
		verify(repository, times(1)).save(any());
		verifyNoInteractions(support);
	}

	@Test
	void paymentAndForfeitCannotCoexist() {
		given(repository.findAllByAuctionIdForUpdate(100L)).willReturn(paymentSources(100000));

		assertThatIllegalStateException().isThrownBy(() -> useCase.createFromForfeit(100L, "레고", seller, 10000));
		verify(repository, never()).save(any());
	}

	private List<Settlement> paymentSources(int amount) {
		return Settlement.createPaymentSources(100L, "레고", seller, system, amount);
	}
}
