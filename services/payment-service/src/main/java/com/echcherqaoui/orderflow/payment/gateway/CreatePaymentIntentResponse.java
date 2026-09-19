package com.echcherqaoui.orderflow.payment.gateway;

public record CreatePaymentIntentResponse(String paymentIntentId,
                                          String clientSecret) {
}
