package com.echcherqaoui.orderflow.inventory.repository;

import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.projection.ItemSummaryDto;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemRepositoryIT  implements WithPostgres {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private EntityManager entityManager;

    private Item item1;
    private Item item2;

    @BeforeEach
    void setUp() {
        itemRepository.deleteAllInBatch();

        item1 = itemRepository.saveAndFlush(newItem("keyboard", 10000L, 10, 10));
        item2 = itemRepository.saveAndFlush(newItem("mouse", 5000L, 20, 5));
    }

    private Item newItem(String name, long priceCents, int total, int remaining) {
        return new Item()
              .setName(name)
              .setPriceCents(priceCents)
              .setTotalEarlyAccessUnits(total)
              .setRemainingUnits(remaining);
    }

    @Test
    @DisplayName("existsByName returns true for existing item and false otherwise")
    void existsByName_checksNameExistence() {
        assertThat(itemRepository.existsByName("keyboard")).isTrue();
        assertThat(itemRepository.existsByName("non-existent-item")).isFalse();
    }

    @Test
    @DisplayName("getItemSummary calculates correct count and total price for matching item IDs")
    void getItemSummary_matchingIds_returnsCountAndSum() {
        Set<UUID> itemIds = Set.of(item1.getId(), item2.getId());

        ItemSummaryDto summary = itemRepository.getItemSummary(itemIds);

        assertThat(summary).isNotNull();
        assertThat(summary.getCount()).isEqualTo(2);
        assertThat(summary.getTotalPriceCents()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("getItemSummary returns 0 count and 0 price when no items match IDs")
    void getItemSummary_nonExistentIds_returnsZeroes() {
        Set<UUID> nonExistentIds = Set.of(UUID.randomUUID(), UUID.randomUUID());

        ItemSummaryDto summary = itemRepository.getItemSummary(nonExistentIds);

        assertThat(summary).isNotNull();
        assertThat(summary.getCount()).isZero();
        assertThat(summary.getTotalPriceCents()).isZero();
    }

    @Test
    @DisplayName("getItemSummary returns 0 count and 0 price for empty item IDs set")
    void getItemSummary_emptySet_returnsZeroes() {
        ItemSummaryDto summary = itemRepository.getItemSummary(Collections.emptySet());

        assertThat(summary).isNotNull();
        assertThat(summary.getCount()).isZero();
        assertThat(summary.getTotalPriceCents()).isZero();
    }

    @Test
    @DisplayName("decrementStockBatch decrements stock for items with remainingUnits > 0 and returns updated count")
    void decrementStockBatch_itemsInStock_decrementsAndReturnsCount() {
        Set<UUID> targetIds = Set.of(item1.getId(), item2.getId());

        int updatedCount = itemRepository.decrementStockBatch(targetIds);

        assertThat(updatedCount).isEqualTo(2);

        entityManager.clear();

        Item reloaded1 = itemRepository.findById(item1.getId()).orElseThrow();
        Item reloaded2 = itemRepository.findById(item2.getId()).orElseThrow();

        assertThat(reloaded1.getRemainingUnits()).isEqualTo(9);
        assertThat(reloaded2.getRemainingUnits()).isEqualTo(4);
    }

    @Test
    @DisplayName("decrementStockBatch ignores items with 0 remainingUnits and only decrements available items")
    void decrementStockBatch_outOfStockItem_skipsZeroStockItem() {
        Item outOfStockItem = itemRepository.saveAndFlush(newItem("headset", 15000L, 10, 0));

        Set<UUID> targetIds = Set.of(item1.getId(), outOfStockItem.getId());

        int updatedCount = itemRepository.decrementStockBatch(targetIds);

        assertThat(updatedCount).isEqualTo(1);

        entityManager.clear();

        Item reloaded1 = itemRepository.findById(item1.getId()).orElseThrow();
        Item reloadedOutOfStock = itemRepository.findById(outOfStockItem.getId()).orElseThrow();

        assertThat(reloaded1.getRemainingUnits()).isEqualTo(9);
        assertThat(reloadedOutOfStock.getRemainingUnits()).isZero();
    }
}