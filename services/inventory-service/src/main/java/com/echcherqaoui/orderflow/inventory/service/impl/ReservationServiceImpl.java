package com.echcherqaoui.orderflow.inventory.service.impl;

import com.echcherqaoui.orderflow.inventory.exception.domain.InvalidReservationException;
import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.projection.ItemSummaryDto;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import com.echcherqaoui.orderflow.inventory.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.echcherqaoui.orderflow.inventory.exception.enums.InventoryErrorCode.EMPTY_ITEM_LIST;
import static com.echcherqaoui.orderflow.inventory.exception.enums.InventoryErrorCode.ITEMS_OUT_OF_STOCK;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;

@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private static final long DEFAULT_TTL_SECONDS = 60;
    private static final long EXTENDED_TTL_SECONDS = 15L * 60L;

    private final InventoryReservationRepository reservationRepository;
    private final ItemRepository itemRepository;

    private void releaseReservations(Set<UUID> reservedItemIds, Set<UUID> reservationIds) {
        // Single DB query: Restore stock for all items
        itemRepository.incrementStockBatch(reservedItemIds);

        // Single DB query: Delete reservation rows in batch
        reservationRepository.deleteAllByIdInBatch(reservationIds);
    }

    private void extendTtl(@NonNull String cartId, @NonNull Long duration) {
        // Extend reservation window without modifying inventory stock
        Instant newExpiry = Instant.now().plusSeconds(duration);
        reservationRepository.updateExpiresAtByCartId(cartId, newExpiry, PENDING);
    }

    /**
     * Idempotent on cartId. All-or-nothing across the full item list in a
     * single DB transaction: one missing/out-of-stock item aborts everything.
     */
    @Transactional
    @Override
    public long reserve(@NonNull String cartId, @NonNull Set<UUID> requestedItemIds) {
        if (requestedItemIds.isEmpty())
            throw new InvalidReservationException(EMPTY_ITEM_LIST);

        ItemSummaryDto summary = itemRepository.getItemSummary(requestedItemIds);

        if (summary == null || summary.getCount() == null || summary.getCount() != requestedItemIds.size())
            throw new OutOfStockException(ITEMS_OUT_OF_STOCK);
        
        long totalPriceCents = summary.getTotalPriceCents();

        List<InventoryReservation> existingReservations = reservationRepository.findByCartIdAndStatus(cartId, PENDING);

        if (!existingReservations.isEmpty()) {
            Set<UUID> reservedItemIds = existingReservations.stream()
                  .map(InventoryReservation::getItemId)
                  .collect(Collectors.toSet());

            // Idempotent replay: extend reservation window without modifying inventory stock
            if (reservedItemIds.equals(requestedItemIds)) {
                this.extendTtl(cartId, DEFAULT_TTL_SECONDS);

                return totalPriceCents;
            }

            Set<UUID> reservationIds = existingReservations.stream()
                  .map(InventoryReservation::getId)
                  .collect(Collectors.toSet());

            // Cancel-and-replace: release the old reservation, then attempt the new one.
            releaseReservations(reservedItemIds, reservationIds);
        }

        int updatedCount = itemRepository.decrementStockBatch(requestedItemIds);

        if (updatedCount != requestedItemIds.size())
            throw new OutOfStockException(ITEMS_OUT_OF_STOCK);

        Instant expiresAt = Instant.now().plusSeconds(DEFAULT_TTL_SECONDS);

        List<InventoryReservation> newReservations = requestedItemIds.stream()
              .map(itemId -> new InventoryReservation()
                    .setCartId(cartId)
                    .setItemId(itemId)
                    .setStatus(PENDING)
                    .setExpiresAt(expiresAt))
              .toList();

        reservationRepository.saveAll(newReservations);

        return totalPriceCents;
    }
}
