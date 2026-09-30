package com.echcherqaoui.orderflow.payment.mockstripe;

import java.util.UUID;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.CANCELED;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.SUCCEEDED;

public record MockPaymentIntent(String paymentIntentId,
                                String idempotencyKey,
                                String clientSecret,
                                Long totalAmountCents,
                                MockPaymentIntentStatus status,
                                int attemptCount,
                                String lastErrorCode) {

    @lombok.NonNull
    public static MockPaymentIntent create(String idempotencyKey, Long totalAmountCents) {
        return new MockPaymentIntent(
              "pi_mock_" + UUID.randomUUID(),
              idempotencyKey,
              "secret_mock_" + UUID.randomUUID(),
              totalAmountCents,
              REQUIRES_PAYMENT_METHOD,
              0,
              null
        );
    }

    @lombok.NonNull
    public MockPaymentIntent withTransientFailure(String errorCode) {
        return new MockPaymentIntent(
              paymentIntentId,
              idempotencyKey,
              clientSecret,
              totalAmountCents,
              REQUIRES_PAYMENT_METHOD,
              attemptCount + 1,
              errorCode
        );
    }

    @lombok.NonNull
    public MockPaymentIntent withTerminalFailure(String errorCode) {
        return new MockPaymentIntent(
              paymentIntentId,
              idempotencyKey,
              clientSecret,
              totalAmountCents,
              CANCELED,
              attemptCount + 1,
              errorCode
        );
    }

    @lombok.NonNull
    public MockPaymentIntent withSuccess() {
        return new MockPaymentIntent(
              paymentIntentId,
              idempotencyKey,
              clientSecret,
              totalAmountCents,
              SUCCEEDED,
              attemptCount + 1,
              null
        );
    }
}