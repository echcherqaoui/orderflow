package com.echcherqaoui.orderflow.payment.dto;

public record RefundCreateParams(@lombok.NonNull String paymentIntentId,
                                 String reason,
                                 @lombok.NonNull String triggerEventId) {
}