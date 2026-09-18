package com.echcherqaoui.orderflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StripeWebhookPayload(String id,
                                   String type,
                                   Data data) {
    public record Data(ObjectData object) {
    }

    public record ObjectData(String id, // payment_intent_id
                             Long amount,
                             String currency,
                             String status,
                             @JsonProperty("last_payment_error")
                             PaymentError lastPaymentError) {
    }

    public record PaymentError(String code,
                               String message) {
    }
}