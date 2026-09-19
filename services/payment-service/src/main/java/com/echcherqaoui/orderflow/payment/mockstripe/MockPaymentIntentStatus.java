package com.echcherqaoui.orderflow.payment.mockstripe;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MockPaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD("requires_payment_method"),
    SUCCEEDED("succeeded"),
    CANCELED("canceled");

    private final String value;
}