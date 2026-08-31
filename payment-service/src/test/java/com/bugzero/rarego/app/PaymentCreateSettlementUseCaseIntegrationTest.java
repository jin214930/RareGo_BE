package com.bugzero.rarego.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementType;
import com.bugzero.rarego.domain.Wallet;
import com.bugzero.rarego.global.config.JpaConfig;
import com.bugzero.rarego.out.PaymentMemberRepository;
import com.bugzero.rarego.out.SettlementRepository;
import com.bugzero.rarego.out.WalletRepository;

@DataJpaTest(properties = "custom.payment.systemMemberId=1")
@Import({PaymentCreateSettlementUseCase.class, PaymentSupport.class, JpaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentCreateSettlementUseCaseIntegrationTest {
	@Autowired
	private PaymentCreateSettlementUseCase useCase;
	@MockitoSpyBean
	private SettlementRepository repository;
	@Autowired
	private PaymentMemberRepository memberRepository;
	@Autowired
	private WalletRepository walletRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transaction;
	private PaymentMember seller;

	@BeforeEach
	void setUp() {
		transaction = new TransactionTemplate(transactionManager);
		repository.deleteAllInBatch();
		walletRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		member(1L);
		seller = member(10L);
	}

	@Test
	void paymentPersistsTwoTypesForSameAuctionAndRepeatedCallDoesNotDuplicate() {
		transaction.executeWithoutResult(status -> useCase.createForPayment(100L, "레고", seller, 100000));
		transaction.executeWithoutResult(status -> useCase.createForPayment(100L, "레고", seller, 100000));

		List<Settlement> result = repository.findAll();
		assertThat(result).hasSize(2);
		assertThat(result).extracting(Settlement::getType)
			.containsExactlyInAnyOrder(SettlementType.SELLER_PROCEEDS, SettlementType.PLATFORM_FEE);
		assertThat(result).extracting(source -> source.getRecipient().getId()).containsExactlyInAnyOrder(10L, 1L);
		assertThat(result.stream().mapToLong(Settlement::getSettlementAmount).sum()).isEqualTo(100000);
	}

	@Test
	void duplicateAuctionAndTypeViolatesDatabaseConstraint() {
		transaction.executeWithoutResult(status -> useCase.createForPayment(100L, "레고", seller, 100000));

		assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
			repository.saveAndFlush(Settlement.create(100L, "레고", seller, 100000))))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(repository.count()).isEqualTo(2);
	}

	@Test
	void secondSourceFailureRollsBackFirstSourceAndBuyerDebit() {
		PaymentMember buyer = member(20L);
		walletRepository.saveAndFlush(Wallet.builder().member(buyer).balance(200000).build());
		doThrow(new DataIntegrityViolationException("수수료 원천 저장 실패"))
			.when(repository).save(argThat(source -> source.getType() == SettlementType.PLATFORM_FEE));

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			Wallet wallet = walletRepository.findByMemberIdForUpdate(20L).orElseThrow();
			wallet.pay(100000);
			useCase.createForPayment(100L, "레고", seller, 100000);
		})).isInstanceOf(DataIntegrityViolationException.class);

		verify(repository).save(argThat(source -> source.getType() == SettlementType.SELLER_PROCEEDS));
		assertThat(repository.count()).isZero();
		assertThat(walletRepository.findByMemberId(20L).orElseThrow().getBalance()).isEqualTo(200000);
	}

	@Test
	void sourceCreationRequiresCallerTransaction() {
		assertThatThrownBy(() -> useCase.createForPayment(100L, "레고", seller, 100000))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(repository.count()).isZero();
	}

	@Test
	void forfeitPersistsProductNameAndSingleSellerSource() {
		transaction.executeWithoutResult(status -> useCase.createFromForfeit(100L, "레고", seller, 10000));

		assertThat(repository.findAll()).singleElement().satisfies(source -> {
			assertThat(source.getProductName()).isEqualTo("레고");
			assertThat(source.getType()).isEqualTo(SettlementType.DEPOSIT_FORFEIT);
			assertThat(source.getSettlementAmount()).isEqualTo(10000);
			assertThat(source.getFeeAmount()).isZero();
		});
	}

	@Test
	void sellerSearchExcludesSystemFeeAndPreservesSalesMetadata() {
		transaction.executeWithoutResult(status -> useCase.createForPayment(100L, "레고", seller, 100000));

		assertThat(repository.searchSettlements(10L, null, null, null, PageRequest.of(0, 10)).getContent())
			.singleElement().satisfies(source -> {
				assertThat(source.getType()).isEqualTo(SettlementType.SELLER_PROCEEDS);
				assertThat(source.getSalesAmount()).isEqualTo(100000);
				assertThat(source.getFeeAmount()).isEqualTo(10000);
				assertThat(source.getSettlementAmount()).isEqualTo(90000);
			});
	}

	@Test
	void recoveryPersistsOnlyMissingTypeEvenWhenSellerSourceIsDone() {
		Settlement proceeds = Settlement.create(100L, "레고", seller, 100000);
		proceeds.complete();
		repository.saveAndFlush(proceeds);

		transaction.executeWithoutResult(status -> {
			assertThat(useCase.hasCompletePaymentSources(100L, "레고", seller, 100000)).isFalse();
			useCase.createForPayment(100L, "레고", seller, 100000);
			assertThat(useCase.hasCompletePaymentSources(100L, "레고", seller, 100000)).isTrue();
		});

		assertThat(repository.count()).isEqualTo(2);
		assertThat(repository.findById(proceeds.getId()).orElseThrow().getStatus()).isEqualTo(proceeds.getStatus());
	}

	private PaymentMember member(Long id) {
		return memberRepository.saveAndFlush(PaymentMember.builder()
			.id(id).publicId("member-" + id).email(id + "@test.com").nickname("member" + id).build());
	}
}
