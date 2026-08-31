package com.bugzero.rarego.app;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.domain.PaymentMember;
import com.bugzero.rarego.domain.Settlement;
import com.bugzero.rarego.domain.SettlementStatus;
import com.bugzero.rarego.domain.SettlementType;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.out.SettlementRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentCreateSettlementUseCase {
	private final SettlementRepository settlementRepository;
	private final PaymentSupport paymentSupport;

	@Value("${custom.payment.systemMemberId}")
	private Long systemMemberId;

	public void validateNotCreated(Long auctionId) {
		if (!settlementRepository.findAllByAuctionIdForUpdate(auctionId).isEmpty()) {
			throw new CustomException(ErrorType.INVALID_ORDER_STATUS);
		}
	}

	public List<Settlement> createForPayment(Long auctionId, String productName, PaymentMember seller,
		int salesAmount) {
		List<Settlement> expected = paymentSources(auctionId, productName, seller, salesAmount);
		return saveMissingSources(auctionId, expected);
	}

	public boolean hasCompletePaymentSources(Long auctionId, String productName, PaymentMember seller,
		int salesAmount) {
		List<Settlement> expected = paymentSources(auctionId, productName, seller, salesAmount);
		List<Settlement> existing = settlementRepository.findAllByAuctionIdForUpdate(auctionId);
		validateExistingSources(existing, expected);
		return existing.size() == expected.size();
	}

	public Settlement createFromForfeit(Long auctionId, String productName, PaymentMember seller, int forfeitAmount) {
		Settlement expected = Settlement.createFromForfeit(auctionId, productName, seller, forfeitAmount);
		return saveMissingSources(auctionId, List.of(expected)).getFirst();
	}

	private List<Settlement> paymentSources(Long auctionId, String productName, PaymentMember seller, int salesAmount) {
		PaymentMember systemMember = paymentSupport.findMemberById(systemMemberId);
		return Settlement.createPaymentSources(auctionId, productName, seller, systemMember, salesAmount);
	}

	private List<Settlement> saveMissingSources(Long auctionId, List<Settlement> expected) {
		List<Settlement> existing = settlementRepository.findAllByAuctionIdForUpdate(auctionId);
		Map<SettlementType, Settlement> byType = validateExistingSources(existing, expected);
		List<Settlement> result = new ArrayList<>();
		for (Settlement source : expected) {
			Settlement saved = byType.get(source.getType());
			result.add(saved != null ? saved : settlementRepository.save(source));
		}
		return result;
	}

	private Map<SettlementType, Settlement> validateExistingSources(List<Settlement> existing,
		List<Settlement> expected) {
		Map<SettlementType, Settlement> byType = new EnumMap<>(SettlementType.class);
		for (Settlement source : existing) {
			Settlement target = expected.stream()
				.filter(candidate -> candidate.getType() == source.getType())
				.findFirst()
				.orElseThrow(() -> conflict(source));
			if (byType.put(source.getType(), source) != null
				|| !Objects.equals(source.getSeller().getId(), target.getSeller().getId())
				|| !Objects.equals(source.getRecipient().getId(), target.getRecipient().getId())
				|| source.getSalesAmount() != target.getSalesAmount()
				|| source.getFeeAmount() != target.getFeeAmount()
				|| source.getSettlementAmount() != target.getSettlementAmount()
				|| !Objects.equals(source.getProductName(), target.getProductName())
				|| (source.getStatus() != SettlementStatus.READY && source.getStatus() != SettlementStatus.PENDING
					&& source.getStatus() != SettlementStatus.DONE)) {
				throw conflict(source);
			}
		}
		return byType;
	}

	private IllegalStateException conflict(Settlement source) {
		return new IllegalStateException("기존 정산 원천이 요청과 일치하지 않습니다. auctionId=" + source.getAuctionId());
	}
}
