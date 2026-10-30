package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.model.ProcessedEvent;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import com.echcherqaoui.orderflow.inventory.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Optional;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Slf4j
public class ItemService {

    private final ItemRepository itemRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxWriter outboxWriter;

    private Item newItem(UUID id, String itemName, Long priceCents) {
        log.info("Item '{}' not found. Creating new item record.", itemName);

        return new Item()
              .setId(id)
              .setName(itemName)
              .setPriceCents(priceCents)
              .setTotalEarlyAccessUnits(0)
              .setRemainingUnits(0);
    }

    @Transactional
    public void provisionStock(@NonNull UUID messageId,
                               @NonNull UUID itemId,
                               @NonNull String itemName,
                               @NonNull Long priceCents,
                               int quantity) {
        if (quantity <= 0)
            throw new IllegalArgumentException("quantity must be positive: " + quantity);

        processedEventRepository.saveAndFlush(new ProcessedEvent(messageId)); // throws on duplicate

        // Lock the row if it exists to prevent concurrent update race conditions
        Optional<Item> existing = itemRepository.findByIdForUpdate(itemId);

        if (existing.isPresent() && !existing.get().hasPrice(priceCents)) {
            log.error("Stock provision rejected for item '{}': price mismatch. MessageId: {}, Existing price: {} cents, Incoming price: {} cents",
                  itemName,
                  messageId,
                  existing.get().getPriceCents(),
                  priceCents
            );

            outboxWriter.publishStockProvisionRejectedEvent(
                  messageId.toString(),
                  itemName,
                  "PRICE_MISMATCH"
            );

            return;
        }

        Item item = existing.orElseGet(() -> newItem(itemId, itemName, priceCents));
        item.addStock(quantity);
        itemRepository.save(item);

        outboxWriter.publishStockProvisionedEvent(
              messageId.toString(),
              itemName,
              quantity
        );

        log.info(
              "Stock increased. MessageId: {}, Item: '{}', Quantity: {}",
              messageId,
              itemName,
              quantity
        );
    }
}