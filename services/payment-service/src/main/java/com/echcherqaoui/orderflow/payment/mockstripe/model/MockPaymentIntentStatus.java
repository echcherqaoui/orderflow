package com.echcherqaoui.orderflow.payment.mockstripe.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MockPaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD("requires_payment_method", "payment_intent.payment_failed"),
    SUCCEEDED("succeeded", "payment_intent.succeeded"),
    CANCELED("canceled", "payment_intent.canceled"),
    REFUNDED("refunded", "charge.refunded");

    private final String value;
    private final String webhookEventType;
}