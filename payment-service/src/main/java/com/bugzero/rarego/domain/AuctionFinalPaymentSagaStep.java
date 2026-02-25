package com.bugzero.rarego.domain;

public enum AuctionFinalPaymentSagaStep {
	INITIATED,
	LOCAL_DEBIT_DONE,
	AUCTION_COMPLETE_SENT,
	SETTLEMENT_READY,
	COMPLETED
}
