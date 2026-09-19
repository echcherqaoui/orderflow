package com.echcherqaoui.orderflow.order.events;

import java.util.UUID;

public record OrderPaymentSessionActiveEvent(@lombok.NonNull UUID orderId,
                                             @lombok.NonNull String paymentIntentId,
                                             @lombok.NonNull String clientSecret) {
}