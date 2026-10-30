package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import com.echcherqaoui.orderflow.inventory.repository.ProcessedEventRepository;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class ItemServiceIT implements WithPostgres {

    @Autowired
    private ItemService itemService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @MockitoBean
    private OutboxWriter outboxWriter;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();
        processedEventRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
    }

    @Nested
    @Transactional
    @DisplayName("provisionStock integration tests")
    class ProvisionStockIntegrationTests {

        @Test
        @DisplayName("provisionStock with zero or negative quantity throws IllegalArgumentException")
        void provisionStock_invalidQuantity_throwsException() {
            UUID messageId = UUID.randomUUID();
            UUID itemId = UUID.randomUUID();
            String itemName = "item-invalid";

            assertThatThrownBy(() -> itemService.provisionStock(messageId, itemId, itemName, 1000L, 0))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("quantity must be positive");

            assertThat(itemRepository.count()).isZero();
        }

        @Test
        @DisplayName("provisionStock with non-existent item creates new item and publishes provisioned event")
        void provisionStock_newItem_success() {
            UUID messageId = UUID.randomUUID();
            String itemName = "new-provisioned-item";
            UUID itemId = UUID.randomUUID();
            long priceCents = 2500L;
            int quantity = 15;

            itemService.provisionStock(messageId, itemId, itemName, priceCents, quantity);

            Item persisted = itemRepository.findByIdForUpdate(itemId).orElseThrow();
            assertThat(persisted.getPriceCents()).isEqualTo(priceCents);
            assertThat(persisted.getTotalEarlyAccessUnits()).isEqualTo(quantity);
            assertThat(persisted.getRemainingUnits()).isEqualTo(quantity);

            assertThat(processedEventRepository.existsById(messageId)).isTrue();
            verify(outboxWriter).publishStockProvisionedEvent(messageId.toString(), itemName, quantity);
        }

        @Test
        @DisplayName("provisionStock with existing item and matching price increments stock correctly")
        void provisionStock_existingItemMatchingPrice_success() {
            UUID messageId = UUID.randomUUID();
            String itemName = "existing-item";
            UUID itemId = UUID.randomUUID();
            long priceCents = 2000L;

            itemRepository.saveAndFlush(
                  new Item()
                        .setId(itemId)
                        .setName(itemName)
                        .setPriceCents(priceCents)
                        .setTotalEarlyAccessUnits(20)
                        .setRemainingUnits(10)
            );

            itemService.provisionStock(messageId, itemId, itemName, priceCents, 5);

            Item updated = itemRepository.findByIdForUpdate(itemId).orElseThrow();
            assertThat(updated.getTotalEarlyAccessUnits()).isEqualTo(25);
            assertThat(updated.getRemainingUnits()).isEqualTo(15);

            assertThat(processedEventRepository.existsById(messageId)).isTrue();
            verify(outboxWriter).publishStockProvisionedEvent(messageId.toString(), itemName, 5);
        }

        @Test
        @DisplayName("provisionStock with price mismatch rejects provision and publishes rejection event")
        void provisionStock_priceMismatch_rejectsAndPublishesRejection() {
            UUID messageId = UUID.randomUUID();
            String itemName = "existing-item-price-mismatch";
            UUID itemId = UUID.randomUUID();
            long existingPrice = 2000L;
            long incomingPrice = 3000L;

            itemRepository.saveAndFlush(
                  new Item()
                        .setId(itemId)
                        .setName(itemName)
                        .setPriceCents(existingPrice)
                        .setTotalEarlyAccessUnits(20)
                        .setRemainingUnits(10)
            );

            itemService.provisionStock(messageId, itemId, itemName, incomingPrice, 5);

            // Verify units were NOT changed
            Item unchanged = itemRepository.findByIdForUpdate(itemId).orElseThrow();
            assertThat(unchanged.getTotalEarlyAccessUnits()).isEqualTo(20);
            assertThat(unchanged.getRemainingUnits()).isEqualTo(10);

            assertThat(processedEventRepository.existsById(messageId)).isTrue();
            verify(outboxWriter).publishStockProvisionRejectedEvent(messageId.toString(), itemName, "PRICE_MISMATCH");
        }
    }
}