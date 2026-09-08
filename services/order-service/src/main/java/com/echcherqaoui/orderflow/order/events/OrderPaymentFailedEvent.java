package com.echcherqaoui.orderflow.order.events;

import java.util.UUID;

public record OrderPaymentFailedEvent(UUID orderId,
                                      String reason) {
}