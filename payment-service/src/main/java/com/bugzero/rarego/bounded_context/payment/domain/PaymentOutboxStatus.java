package com.bugzero.rarego.bounded_context.payment.domain;

public enum PaymentOutboxStatus {
    PENDING,
    SENT,
    FAILED
}

