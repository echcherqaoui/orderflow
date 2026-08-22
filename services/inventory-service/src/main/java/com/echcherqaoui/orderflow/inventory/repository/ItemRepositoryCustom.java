package com.echcherqaoui.orderflow.inventory.repository;

import java.util.Map;
import java.util.UUID;

public interface ItemRepositoryCustom {
    void incrementStockBatchByCounts(Map<UUID, Integer> itemCounts);
}