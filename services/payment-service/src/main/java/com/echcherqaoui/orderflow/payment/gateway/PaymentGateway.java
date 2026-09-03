package com.echcherqaoui.orderflow.payment.gateway;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.exception.domain.PaymentGatewayTransientException;
import org.springframework.resilience.annotation.Retryable;

public interface PaymentGateway {
    @Retryable(
          includes = PaymentGatewayTransientException.class,
          maxRetries = 2,
          delay = 200,
          multiplier = 2.0
    )
    CreatePaymentIntentResponse createIntent(String idempotencyKey, long totalAmountCents);

    void cancelIntent(String paymentIntentId);
}