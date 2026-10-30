package com.echcherqaoui.orderflow.inventory.service;

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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
    @DisplayName("provisionStock tests")
    class ProvisionStockTests {

        @Test
        @DisplayName("provisionStock with zero or negative quantity throws IllegalArgumentException")
        void provisionStock_invalidQuantity_throwsException() {
            UUID messageId = UUID.randomUUID();
            String itemName = "item-1";
            UUID itemId = UUID.randomUUID();
            long priceCents = 1000L;

            assertThatThrownBy(() -> itemService.provisionStock(messageId,itemId, itemName, priceCents, 0))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("quantity must be positive");

            assertThatThrownBy(() -> itemService.provisionStock(messageId,itemId, itemName, priceCents, -5))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("quantity must be positive");

            verifyNoInteractions(processedEventRepository, itemRepository, outboxWriter);
        }

        @Test
        @DisplayName("provisionStock with non-existent item creates new item and publishes provisioned event")
        void provisionStock_newItem_success() {
            UUID messageId = UUID.randomUUID();
            String itemName = "new-item";
            UUID itemId = UUID.randomUUID();
            long priceCents = 1500L;
            int quantity = 10;

            when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.empty());

            itemService.provisionStock(messageId,itemId, itemName, priceCents, quantity);

            verify(processedEventRepository).saveAndFlush(processedEventCaptor.capture());
            assertThat(processedEventCaptor.getValue().getEventId()).isEqualTo(messageId);

            verify(itemRepository).findByIdForUpdate(itemId);
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
            UUID itemId = UUID.randomUUID();
            long priceCents = 2000L;
            int quantity = 5;

            Item existingItem = new Item()
                  .setId(UUID.randomUUID())
                  .setName(itemName)
                  .setPriceCents(priceCents)
                  .setTotalEarlyAccessUnits(20)
                  .setRemainingUnits(15);

            when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(existingItem));

            itemService.provisionStock(messageId, itemId, itemName, priceCents, quantity);

            verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
            verify(itemRepository).findByIdForUpdate(itemId);
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
            UUID itemId = UUID.randomUUID();
            long existingPrice = 2000L;
            long incomingPrice = 2500L;
            int quantity = 5;

            Item existingItem = new Item()
                  .setId(UUID.randomUUID())
                  .setName(itemName)
                  .setPriceCents(existingPrice)
                  .setTotalEarlyAccessUnits(20)
                  .setRemainingUnits(15);

            when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(existingItem));

            itemService.provisionStock(messageId, itemId, itemName, incomingPrice, quantity);

            verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
            verify(itemRepository).findByIdForUpdate(itemId);
            verify(outboxWriter).publishStockProvisionRejectedEvent(messageId.toString(), itemName, "PRICE_MISMATCH");

            verifyNoMoreInteractions(processedEventRepository, itemRepository, outboxWriter);
        }
    }
}