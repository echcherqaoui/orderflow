package com.echcherqaoui.orderflow.inventory.repository;

import com.echcherqaoui.orderflow.inventory.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.inventory.model.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ItemRepositoryCustomImplIT extends AbstractIntegrationTest {

    @Autowired
    private ItemRepositoryCustomImpl itemRepositoryCustom;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    private Item item1;
    private Item item2;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        item1 = itemRepository.saveAndFlush(newItem(100, 10));
        item2 = itemRepository.saveAndFlush(newItem(50, 20));

    }

    private Item newItem(int total, int remaining) {
        return new Item()
              .setName("test-item-" + UUID.randomUUID())
              .setPriceCents(1000)
              .setTotalEarlyAccessUnits(total)
              .setRemainingUnits(remaining);
    }

    @Test
    @DisplayName("empty itemCounts map exits early without running batch update")
    void incrementStockBatchByCounts_emptyMap_noOp() {
        itemRepositoryCustom.incrementStockBatchByCounts(Collections.emptyMap());

        assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
        assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(20);
    }

    @Test
    @DisplayName("valid itemCounts map increments remaining_units for corresponding items")
    void incrementStockBatchByCounts_validMap_updatesUnits() {
        Map<UUID, Integer> increments = Map.of(
              item1.getId(), 5,
              item2.getId(), 15
        );

        itemRepositoryCustom.incrementStockBatchByCounts(increments);

        assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(15);
        assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(35);
    }

    @Test
    @DisplayName("non-existent item UUIDs in map are ignored without throwing exception")
    void incrementStockBatchByCounts_nonExistentUuid_ignoresMissingRows() {
        UUID nonExistentId = UUID.randomUUID();
        Map<UUID, Integer> increments = Map.of(
              item1.getId(), 10,
              nonExistentId, 100
        );

        itemRepositoryCustom.incrementStockBatchByCounts(increments);

        assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(20);
        assertThat(itemRepository.existsById(nonExistentId)).isFalse();
    }
}