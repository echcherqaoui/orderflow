package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.dto.request.CreateItemRequest;
import com.echcherqaoui.orderflow.inventory.dto.response.ItemResponse;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemAlreadyExistsException;
import com.echcherqaoui.orderflow.inventory.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import com.echcherqaoui.orderflow.inventory.repository.ProcessedEventRepository;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
import jakarta.validation.ConstraintViolationException;
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
    @DisplayName("createItem integration tests")
    class CreateItemIntegrationTests {

        @Test
        @DisplayName("existing itemName throws ItemAlreadyExistsException")
        void createItem_existingName_throwsItemAlreadyExistsException() {
            String itemName = "test-item-" + UUID.randomUUID();
            itemRepository.saveAndFlush(new Item()
                  .setName(itemName)
                  .setPriceCents(1000L)
                  .setTotalEarlyAccessUnits(10)
                  .setRemainingUnits(10));

            CreateItemRequest request = new CreateItemRequest(itemName, 2000L, 20);

            assertThatThrownBy(() -> itemService.createItem(request))
                  .isInstanceOf(ItemAlreadyExistsException.class)
                  .hasMessageContaining(itemName);

            assertThat(itemRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("valid request persists item to PostgreSQL with matching remainingUnits")
        void createItem_validRequest_persistsAndReturnsResponse() {
            String itemName = "test-item-" + UUID.randomUUID();
            CreateItemRequest request = new CreateItemRequest(itemName, 1500L, 50);

            ItemResponse response = itemService.createItem(request);

            assertThat(response).isNotNull();
            assertThat(response.id()).isNotNull();
            assertThat(response.name()).isEqualTo(itemName);
            assertThat(response.priceCents()).isEqualTo(1500L);
            assertThat(response.totalEarlyAccessUnits()).isEqualTo(50);
            assertThat(response.remainingUnits()).isEqualTo(50);

            Item persistedItem = itemRepository.findById(response.id()).orElseThrow();

            assertThat(persistedItem.getName()).isEqualTo(itemName);
            assertThat(persistedItem.getPriceCents()).isEqualTo(1500L);
            assertThat(persistedItem.getTotalEarlyAccessUnits()).isEqualTo(50);
            assertThat(persistedItem.getRemainingUnits()).isEqualTo(50);
        }

        @Test
        @DisplayName("invalid CreateItemRequest triggers Spring MethodValidation post-processor")
        void createItem_invalidDto_throwsConstraintViolationException() {
            CreateItemRequest invalidRequest = new CreateItemRequest("", -100L, 10);

            assertThatThrownBy(() -> itemService.createItem(invalidRequest))
                  .isInstanceOf(ConstraintViolationException.class);

            assertThat(itemRepository.count()).isZero();
        }
    }

    @Nested
    @Transactional
    @DisplayName("provisionStock integration tests")
    class ProvisionStockIntegrationTests {

        @Test
        @DisplayName("provisionStock with zero or negative quantity throws IllegalArgumentException")
        void provisionStock_invalidQuantity_throwsException() {
            UUID messageId = UUID.randomUUID();
            String itemName = "item-invalid";

            assertThatThrownBy(() -> itemService.provisionStock(messageId, itemName, 1000L, 0))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("quantity must be positive");

            assertThat(itemRepository.count()).isZero();
        }

        @Test
        @DisplayName("provisionStock with non-existent item creates new item and publishes provisioned event")
        void provisionStock_newItem_success() {
            UUID messageId = UUID.randomUUID();
            String itemName = "new-provisioned-item";
            long priceCents = 2500L;
            int quantity = 15;

            itemService.provisionStock(messageId, itemName, priceCents, quantity);

            Item persisted = itemRepository.findByNameForUpdate(itemName).orElseThrow();
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
            long priceCents = 2000L;

            itemRepository.saveAndFlush(new Item()
                  .setName(itemName)
                  .setPriceCents(priceCents)
                  .setTotalEarlyAccessUnits(20)
                  .setRemainingUnits(10));

            itemService.provisionStock(messageId, itemName, priceCents, 5);

            Item updated = itemRepository.findByNameForUpdate(itemName).orElseThrow();
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
            long existingPrice = 2000L;
            long incomingPrice = 3000L;

            itemRepository.saveAndFlush(new Item()
                  .setName(itemName)
                  .setPriceCents(existingPrice)
                  .setTotalEarlyAccessUnits(20)
                  .setRemainingUnits(10));

            itemService.provisionStock(messageId, itemName, incomingPrice, 5);

            // Verify units were NOT changed
            Item unchanged = itemRepository.findByNameForUpdate(itemName).orElseThrow();
            assertThat(unchanged.getTotalEarlyAccessUnits()).isEqualTo(20);
            assertThat(unchanged.getRemainingUnits()).isEqualTo(10);

            assertThat(processedEventRepository.existsById(messageId)).isTrue();
            verify(outboxWriter).publishStockProvisionRejectedEvent(messageId.toString(), itemName, "PRICE_MISMATCH");
        }
    }
}