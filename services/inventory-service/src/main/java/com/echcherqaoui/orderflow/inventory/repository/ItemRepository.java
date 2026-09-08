package com.echcherqaoui.orderflow.inventory.repository;

import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.projection.ItemSummaryDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID>, ItemRepositoryCustom {

    boolean existsByName(String name);

    @Query("""
           SELECT COUNT(i.id) AS count,
                 COALESCE(SUM(i.priceCents), 0L) AS totalPriceCents
           FROM Item i
           WHERE i.id IN :itemIds
          """)
    ItemSummaryDto getItemSummary(@Param("itemIds") Set<UUID> itemIds);

    @Modifying
    @Query("""
           UPDATE Item i
           SET i.remainingUnits = i.remainingUnits - 1
                WHERE i.id IN :itemIds AND i.remainingUnits > 0
          """)
    int decrementStockBatch(@Param("itemIds") Set<UUID> itemIds);

    @Modifying
    @Query("""
           UPDATE Item i
           SET i.remainingUnits = i.remainingUnits + 1
               WHERE i.id IN :itemIds
          """)
    void incrementStockBatch(@Param("itemIds") Set<UUID> itemIds);
}