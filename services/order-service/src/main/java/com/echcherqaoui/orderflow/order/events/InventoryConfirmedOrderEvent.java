package com.echcherqaoui.orderflow.order.events;

import lombok.NonNull;

import java.util.UUID;

public record InventoryConfirmedOrderEvent(@NonNull UUID orderId) {
}