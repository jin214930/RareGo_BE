package com.bugzero.rarego.global.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum KafkaTopics {
	// Member events
	MEMBER_UPDATE("member-updated"),
	MEMBER_JOINED("member-joined"),

	// Auction events
	AUCTION_ENDED("auction-ended"),
	AUCTION_STARTED("auction-started"),
	AUCTION_OUTBID("auction-outbid"),
	AUCTION_RELISTED("auction-relisted"),

	// Payment events
	PAYMENT_SETTLEMENT_FINISHED("payment-settlement-finished"),
	PAYMENT_AUCTION_COMPLETED("payment-auction-completed"),
	PAYMENT_AUCTION_EXPIRING_SOON("payment-auction-expiring-soon"),
	PAYMENT_TIMEOUT("payment-timeout"),

	// Product events
	AUCTION_INFO_MANAGEMENT("auction-info-management");

	private final String topicName;
}
