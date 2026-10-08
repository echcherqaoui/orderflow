package com.echcherqaoui.orderflow.order.events;

import java.util.UUID;

public record PaymentRefundFailedOrderEvent(UUID orderId,
                                            String reason) {
}