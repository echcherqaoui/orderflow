package com.echcherqaoui.orderflow.order.events;

import java.util.UUID;

public record OrderProvisioningFailedEvent(UUID orderId,
                                           String reason) {
}