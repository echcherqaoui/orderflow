package com.echcherqaoui.orderflow.inventory.dto.response;

import com.echcherqaoui.orderflow.inventory.model.Item;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

public record ItemResponse(UUID id,
                           String name,
                           long priceCents,
                           int totalEarlyAccessUnits,
                           int remainingUnits,
                           Instant createdAt,
                           Instant updatedAt) {
    @NonNull
    public static ItemResponse fromEntity(@NonNull Item item) {
        return new ItemResponse(
              item.getId(),
              item.getName(),
              item.getPriceCents(),
              item.getTotalEarlyAccessUnits(),
              item.getRemainingUnits(),
              item.getCreatedAt(),
              item.getUpdatedAt()
        );
    }
}