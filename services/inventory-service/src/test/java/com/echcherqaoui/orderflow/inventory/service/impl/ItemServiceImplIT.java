package com.echcherqaoui.orderflow.inventory.service.impl;

import com.echcherqaoui.orderflow.inventory.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.inventory.dto.request.CreateItemRequest;
import com.echcherqaoui.orderflow.inventory.dto.response.ItemResponse;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemAlreadyExistsException;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ItemServiceImplIT extends AbstractIntegrationTest {

    @Autowired
    private ItemServiceImpl itemService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
    }

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