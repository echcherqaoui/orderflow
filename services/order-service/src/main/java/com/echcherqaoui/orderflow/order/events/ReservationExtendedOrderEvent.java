package com.echcherqaoui.orderflow.order.events;

import java.time.Instant;
import java.util.UUID;

public record ReservationExtendedOrderEvent(UUID orderId,
                                            Instant newExpiresAt) {
}