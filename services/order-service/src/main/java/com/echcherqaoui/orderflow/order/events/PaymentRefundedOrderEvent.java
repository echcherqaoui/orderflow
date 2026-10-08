package com.echcherqaoui.orderflow.order.events;

import java.util.UUID;

public record PaymentRefundedOrderEvent(UUID orderId,
                                        String reason) {
}