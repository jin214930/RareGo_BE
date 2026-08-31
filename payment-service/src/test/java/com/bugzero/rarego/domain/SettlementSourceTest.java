package com.bugzero.rarego.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettlementSourceTest {

	private final PaymentMember seller = PaymentMember.builder().id(10L).build();
	private final PaymentMember system = PaymentMember.builder().id(1L).build();

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 9, 10, 99999, 100000, Integer.MAX_VALUE})
	void paymentSourcesPreserveTotalAndRecipients(int salesAmount) {
		List<Settlement> sources = Settlement.createPaymentSources(100L, "레고", seller, system, salesAmount);

		assertThat(sources).hasSize(2);
		Settlement proceeds = sources.get(0);
		Settlement fee = sources.get(1);
		assertThat(proceeds.getType()).isEqualTo(SettlementType.SELLER_PROCEEDS);
		assertThat(proceeds.getRecipient()).isSameAs(seller);
		assertThat(fee.getType()).isEqualTo(SettlementType.PLATFORM_FEE);
		assertThat(fee.getRecipient()).isSameAs(system);
		assertThat(fee.getSettlementAmount()).isEqualTo(salesAmount / 10);
		assertThat((long)proceeds.getSettlementAmount() + fee.getSettlementAmount()).isEqualTo(salesAmount);
		assertThat(sources).allSatisfy(source -> {
			assertThat(source.getAuctionId()).isEqualTo(100L);
			assertThat(source.getSeller()).isSameAs(seller);
			assertThat(source.getSalesAmount()).isEqualTo(salesAmount);
			assertThat(source.getFeeAmount()).isEqualTo(salesAmount / 10);
			assertThat(source.getProductName()).isEqualTo("레고");
			assertThat(source.getStatus()).isEqualTo(SettlementStatus.READY);
		});
	}

	@Test
	void forfeitCreatesSellerSourceWithProductNameAndNoFee() {
		Settlement source = Settlement.createFromForfeit(100L, "레고", seller, 10000);

		assertThat(source.getType()).isEqualTo(SettlementType.DEPOSIT_FORFEIT);
		assertThat(source.getRecipient()).isSameAs(seller);
		assertThat(source.getProductName()).isEqualTo("레고");
		assertThat(source.getFeeAmount()).isZero();
		assertThat(source.getSettlementAmount()).isEqualTo(10000);
	}

	@Test
	void negativeAmountIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(
			() -> Settlement.createPaymentSources(100L, "레고", seller, system, -1));
		assertThatIllegalArgumentException().isThrownBy(
			() -> Settlement.createFromForfeit(100L, "레고", seller, -1));
	}
}
