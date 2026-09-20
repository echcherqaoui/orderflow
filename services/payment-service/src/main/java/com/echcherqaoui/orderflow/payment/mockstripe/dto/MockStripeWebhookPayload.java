package com.echcherqaoui.orderflow.payment.mockstripe.dto;

public record MockStripeWebhookPayload(String id,
                                       String type,
                                       Data data) {
    public record Data(ObjectData object) {
    }

    public record ObjectData(String id,
                             Long amount,
                             String currency,
                             String status,
                             PaymentError lastPaymentError) {
    }

    public record PaymentError(String code,
                               String message) {
    }
}