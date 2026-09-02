package com.echcherqaoui.orderflow.payment.gateway;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;

public interface PaymentGateway {
    CreatePaymentIntentResponse createIntent(String orderId, long totalAmountCents);

    void cancelIntent(String paymentIntentId);
}