package com.echcherqaoui.orderflow.entitlement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BatchIngestionRequest(@NotBlank String itemName,
                                    @NotNull Long priceCents,
                                    @NotEmpty List<@NotBlank String> keys) {
}