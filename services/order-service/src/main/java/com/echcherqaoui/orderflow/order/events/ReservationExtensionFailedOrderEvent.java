package com.echcherqaoui.orderflow.order.events;

import java.util.UUID;

public record ReservationExtensionFailedOrderEvent(UUID orderId,
                                                   String reason) {
}