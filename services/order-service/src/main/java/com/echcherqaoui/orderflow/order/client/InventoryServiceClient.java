package com.echcherqaoui.orderflow.order.client;

import java.util.List;

public interface InventoryServiceClient {
    record ReservationResult(List<String> reservedItemIds, long totalPriceCents) {}

    ReservationResult reserveInventory(String cartId, List<String> itemIds);
}