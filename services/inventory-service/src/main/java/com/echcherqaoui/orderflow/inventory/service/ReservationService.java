package com.echcherqaoui.orderflow.inventory.service;

import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.UUID;

public interface ReservationService {
    void reserve(@NonNull String cartId, @NonNull Set<UUID> requestedItemIds);
}
