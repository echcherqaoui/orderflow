package com.echcherqaoui.orderflow.payment.dto;

public record CreatePaymentIntentResponse(String paymentIntentId,
                                          String clientSecret) {
}
