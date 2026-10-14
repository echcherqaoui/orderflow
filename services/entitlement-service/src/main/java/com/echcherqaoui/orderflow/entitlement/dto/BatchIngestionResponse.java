package com.echcherqaoui.orderflow.entitlement.dto;

public record BatchIngestionResponse(String message,
                                     String productId,
                                     int keysAdded) {
}