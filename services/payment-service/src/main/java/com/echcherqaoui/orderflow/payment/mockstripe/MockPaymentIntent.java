package com.echcherqaoui.orderflow.payment.mockstripe;

import java.util.UUID;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;

public record MockPaymentIntent(String paymentIntentId,
                                String clientSecret,
                                Long totalAmountCents,
                                MockPaymentIntentStatus status) {
    @lombok.NonNull
    public static MockPaymentIntent create(Long totalAmountCents) {
        return new MockPaymentIntent(
              "pi_mock_" + UUID.randomUUID(),
              "secret_mock_" + UUID.randomUUID(),
              totalAmountCents,
              REQUIRES_PAYMENT_METHOD
        );
    }
}