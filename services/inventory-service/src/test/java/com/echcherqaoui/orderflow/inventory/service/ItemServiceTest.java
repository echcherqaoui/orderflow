package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.dto.request.CreateItemRequest;
import com.echcherqaoui.orderflow.inventory.dto.response.ItemResponse;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemAlreadyExistsException;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemService itemService;

    @Captor
    private ArgumentCaptor<Item> itemCaptor;

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