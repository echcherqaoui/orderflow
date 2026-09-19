package com.echcherqaoui.orderflow.payment.gateway;

import org.springframework.resilience.annotation.Retryable;

public interface PaymentGateway {
    @Retryable(
          includes = PaymentGatewayTransientException.class,
          maxRetries = 2,
          delay = 200,
          multiplier = 2.0
    )
    CreatePaymentIntentResponse createIntent(String idempotencyKey, long totalAmountCents);

    @Retryable(
          includes = PaymentGatewayTransientException.class,
          maxRetries = 2,
          delay = 200,
          multiplier = 2.0
    )
    void cancelIntent(String paymentIntentId);
}