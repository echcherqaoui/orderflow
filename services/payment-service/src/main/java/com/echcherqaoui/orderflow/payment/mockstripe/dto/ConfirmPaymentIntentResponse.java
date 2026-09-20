package com.echcherqaoui.orderflow.payment.mockstripe.dto;

public record ConfirmPaymentIntentResponse(String id,
                                           String status,
                                           String clientSecret) {
}