package com.echcherqaoui.orderflow.order.events;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

public record OrderCancelledEvent(@NonNull UUID orderId,
                                  @NonNull String reason) {
}