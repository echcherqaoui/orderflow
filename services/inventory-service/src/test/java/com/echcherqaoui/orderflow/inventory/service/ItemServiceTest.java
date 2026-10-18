package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.dto.request.CreateItemRequest;
import com.echcherqaoui.orderflow.inventory.dto.response.ItemResponse;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemAlreadyExistsException;
import com.echcherqaoui.orderflow.inventory.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.model.ProcessedEvent;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import com.echcherqaoui.orderflow.inventory.repository.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private ItemService itemService;

    @Captor
    private ArgumentCaptor<Item> itemCaptor;

    @Captor
    private ArgumentCaptor<ProcessedEvent> processedEventCaptor;

    @Nested
    @DisplayName("createItem tests")
    class CreateItemTests {

        @Test
        @DisplayName("existing itemName throws ItemAlreadyExistsException")
        void createItem_existingName_throwsItemAlreadyExistsException() {
            String name = "test-item-1";
            CreateItemRequest request = new CreateItemRequest(name, 1000L, 50);

            when(itemRepository.existsByName(name)).thenReturn(true);

            assertThatThrownBy(() -> itemService.createItem(request))
                  .isInstanceOf(ItemAlreadyExistsException.class)
                  .hasMessageContaining(name);

            verify(itemRepository).existsByName(name);
            verifyNoMoreInteractions(itemRepository);
        }

        @Test
        @DisplayName("valid request creates item and sets remainingUnits equal to totalEarlyAccessUnits")
        void createItem_validRequest_createsAndReturnsItemResponse() {
            String name = "test-item-2";
            CreateItemRequest request = new CreateItemRequest(name, 2500L, 100);

            UUID generatedId = UUID.randomUUID();
            Instant now = Instant.now();

            when(itemRepository.existsByName(name)).thenReturn(false);
            when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> {
                Item item = invocation.getArgument(0);
                return item
                      .setId(generatedId)
                      .setCreatedAt(now)
                      .setUpdatedAt(now);
            });

            ItemResponse response = itemService.createItem(request);

            verify(itemRepository).save(itemCaptor.capture());
            Item savedItem = itemCaptor.getValue();

            assertThat(savedItem.getName()).isEqualTo(name);
            assertThat(savedItem.getPriceCents()).isEqualTo(2500L);
            assertThat(savedItem.getTotalEarlyAccessUnits()).isEqualTo(100);
            assertThat(savedItem.getRemainingUnits()).isEqualTo(100);

            assertThat(response.id()).isEqualTo(generatedId);
            assertThat(response.name()).isEqualTo(name);
            assertThat(response.priceCents()).isEqualTo(2500L);
            assertThat(response.totalEarlyAccessUnits()).isEqualTo(100);
            assertThat(response.remainingUnits()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("provisionStock tests")
    class ProvisionStockTests {

        @Test
        @DisplayName("provisionStock with zero or negative quantity throws IllegalArgumentException")
        void provisionStock_invalidQuantity_throwsException() {
            UUID messageId = UUID.randomUUID();
            String itemName = "item-1";
            long priceCents = 1000L;

            assertThatThrownBy(() -> itemService.provisionStock(messageId, itemName, priceCents, 0))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("quantity must be positive");

            assertThatThrownBy(() -> itemService.provisionStock(messageId, itemName, priceCents, -5))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("quantity must be positive");

            verifyNoInteractions(processedEventRepository, itemRepository, outboxWriter);
        }

        @Test
        @DisplayName("provisionStock with non-existent item creates new item and publishes provisioned event")
        void provisionStock_newItem_success() {
            UUID messageId = UUID.randomUUID();
            String itemName = "new-item";
            long priceCents = 1500L;
            int quantity = 10;

            when(itemRepository.findByNameForUpdate(itemName)).thenReturn(Optional.empty());

            itemService.provisionStock(messageId, itemName, priceCents, quantity);

            verify(processedEventRepository).saveAndFlush(processedEventCaptor.capture());
            assertThat(processedEventCaptor.getValue().getEventId()).isEqualTo(messageId);

            verify(itemRepository).findByNameForUpdate(itemName);
            verify(itemRepository).save(itemCaptor.capture());

            Item savedItem = itemCaptor.getValue();
            assertThat(savedItem.getName()).isEqualTo(itemName);
            assertThat(savedItem.getPriceCents()).isEqualTo(priceCents);
            assertThat(savedItem.getTotalEarlyAccessUnits()).isEqualTo(quantity);
            assertThat(savedItem.getRemainingUnits()).isEqualTo(quantity);

            verify(outboxWriter).publishStockProvisionedEvent(messageId.toString(), itemName, quantity);
            verifyNoMoreInteractions(processedEventRepository, itemRepository, outboxWriter);
        }

        @Test
        @DisplayName("provisionStock with existing item and matching price increments stock and publishes provisioned event")
        void provisionStock_existingItemMatchingPrice_success() {
            UUID messageId = UUID.randomUUID();
            String itemName = "existing-item";
            long priceCents = 2000L;
            int quantity = 5;

            Item existingItem = new Item()
                  .setId(UUID.randomUUID())
                  .setName(itemName)
                  .setPriceCents(priceCents)
                  .setTotalEarlyAccessUnits(20)
                  .setRemainingUnits(15);

            when(itemRepository.findByNameForUpdate(itemName)).thenReturn(Optional.of(existingItem));

            itemService.provisionStock(messageId, itemName, priceCents, quantity);

            verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
            verify(itemRepository).findByNameForUpdate(itemName);
            verify(itemRepository).save(itemCaptor.capture());

            Item savedItem = itemCaptor.getValue();
            assertThat(savedItem.getTotalEarlyAccessUnits()).isEqualTo(25);
            assertThat(savedItem.getRemainingUnits()).isEqualTo(20);

            verify(outboxWriter).publishStockProvisionedEvent(messageId.toString(), itemName, quantity);
            verifyNoMoreInteractions(processedEventRepository, itemRepository, outboxWriter);
        }

        @Test
        @DisplayName("provisionStock with price mismatch rejects provision and publishes rejection event")
        void provisionStock_priceMismatch_rejectsAndPublishesRejection() {
            UUID messageId = UUID.randomUUID();
            String itemName = "existing-item";
            long existingPrice = 2000L;
            long incomingPrice = 2500L;
            int quantity = 5;

            Item existingItem = new Item()
                  .setId(UUID.randomUUID())
                  .setName(itemName)
                  .setPriceCents(existingPrice)
                  .setTotalEarlyAccessUnits(20)
                  .setRemainingUnits(15);

            when(itemRepository.findByNameForUpdate(itemName)).thenReturn(Optional.of(existingItem));

            itemService.provisionStock(messageId, itemName, incomingPrice, quantity);

            verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
            verify(itemRepository).findByNameForUpdate(itemName);
            verify(outboxWriter).publishStockProvisionRejectedEvent(messageId.toString(), itemName, "PRICE_MISMATCH");

            verifyNoMoreInteractions(processedEventRepository, itemRepository, outboxWriter);
        }
    }
}