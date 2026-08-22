package com.echcherqaoui.orderflow.inventory.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateItemRequest(@NotBlank(message = "Item name cannot be blank") String name,
                                @Min(value = 0, message = "Price cannot be negative") long priceCents,
                                @Min(value = 0, message = "Total units cannot be negative") int totalEarlyAccessUnits) {
}