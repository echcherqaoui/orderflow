package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.exception.domain.InvalidReservationException;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemNotFoundException;
import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.projection.ItemSummaryDto;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.CONCURRENT_RESERVATION_ATTEMPT;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.EMPTY_ITEM_LIST;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.ITEMS_NOT_FOUND;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.ITEMS_OUT_OF_STOCK;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private static final long DEFAULT_TTL_SECONDS = 60;
    private static final long EXTENDED_TTL_SECONDS = 15L * 60L;
    private static final String FAILURE_REASON_EXPIRED = "RESERVATION_EXPIRED";

    private final InventoryReservationRepository reservationRepository;
    private final ItemRepository itemRepository;
    private final OutboxWriter outboxWriter;

    private void releaseReservations(Set<UUID> reservedItemIds, Set<UUID> reservationIds) {
        // Single DB query: Restore stock for all items
        itemRepository.incrementStockBatch(reservedItemIds);

        // Single DB query: Delete reservation rows in batch
        reservationRepository.deleteAllByIdInBatch(reservationIds);
    }

    /**
     * Idempotent on cartId. All-or-nothing across the full item list in a
     * single DB transaction: one missing/out-of-stock item aborts everything.
     */
    @Transactional
    public Long reserve(@lombok.NonNull String cartId,
                        @lombok.NonNull Set<UUID> requestedItemIds) {
        if (requestedItemIds.isEmpty())
            throw new InvalidReservationException(EMPTY_ITEM_LIST);

        ItemSummaryDto summary = itemRepository.getItemSummary(requestedItemIds);

        if (summary == null || summary.getCount() == null || summary.getCount() != requestedItemIds.size())
            throw new ItemNotFoundException(ITEMS_NOT_FOUND);
        
        long totalPriceCents = summary.getTotalPriceCents();

        List<InventoryReservation> existingReservations = reservationRepository.findByCartIdAndStatus(cartId, PENDING);

        if (!existingReservations.isEmpty()) {
            Set<UUID> reservedItemIds = existingReservations.stream()
                  .map(InventoryReservation::getItemId)
                  .collect(Collectors.toSet());

            // Idempotent replay: extend reservation window without modifying inventory stock
            if (reservedItemIds.equals(requestedItemIds)) {
                Instant newExpiry = Instant.now().plusSeconds(DEFAULT_TTL_SECONDS);
                reservationRepository.updateExpiresAtByCartId(cartId, newExpiry, PENDING);

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
                    .setExpiresAt(expiresAt)
              ).toList();

        try {
            reservationRepository.saveAllAndFlush(newReservations);
        } catch (DataIntegrityViolationException e) {
            log.warn( "Concurrent reservation attempt detected for cart {}", cartId);
            throw new InvalidReservationException(CONCURRENT_RESERVATION_ATTEMPT); // new error code, 409
        }

        return totalPriceCents;
    }

    @Transactional
    public void extendReservation(@lombok.NonNull String cartId,
                                  @lombok.NonNull String orderId,
                                  @lombok.NonNull String correlationId,
                                  @lombok.NonNull String messageId) {
        Instant now = Instant.now();
        Instant newExpiresAt = now.plusSeconds(EXTENDED_TTL_SECONDS);
        UUID orderUuid = UUID.fromString(orderId);

        int rowsUpdated = reservationRepository.extendReservationAndSetOrderId(
              newExpiresAt,
              orderUuid,
              cartId,
              now
        );

        if (rowsUpdated == 0) {
            log.warn(
                  "Failed to extend reservation for cart {}: Record missing or expired (orderId: {})",
                  cartId, correlationId
            );

            outboxWriter.publishReservationExtensionFailedEvent(
                  orderUuid,
                  messageId,
                  cartId,
                  FAILURE_REASON_EXPIRED
            );

            return;
        }

        outboxWriter.publishReservationExtendedEvent(
              orderUuid,
              messageId,
              cartId,
              newExpiresAt
        );

        log.info(
              "Extended reservation TTL by {} mins for cart {} (orderId: {}, triggerMsg: {})",
              EXTENDED_TTL_SECONDS/60,
              cartId,
              correlationId,
              messageId
        );
    }

    @Transactional
    public void releaseReservation(@lombok.NonNull String cartId,
                                   @lombok.NonNull String correlationId,
                                   @lombok.NonNull String messageId) {
        List<InventoryReservation> reservations = reservationRepository.findByCartIdAndStatus(cartId, PENDING);
        UUID orderUuid = UUID.fromString(correlationId);

        if (reservations.isEmpty()) {
            log.info(
                  "No active reservation found to release for cart {} (orderId: {})",
                  cartId,
                  correlationId
            );
            outboxWriter.publishInventoryReleasedEvent(orderUuid, messageId, cartId);

            return;
        }

        Set<UUID> reservedItemIds = reservations.stream()
              .map(InventoryReservation::getItemId)
              .collect(Collectors.toSet());

        Set<UUID> reservationIds = reservations.stream()
              .map(InventoryReservation::getId)
              .collect(Collectors.toSet());

        releaseReservations(reservedItemIds, reservationIds);

        outboxWriter.publishInventoryReleasedEvent(
              UUID.fromString(correlationId),
              messageId,
              cartId
        );

        log.info(
              "Released {} reserved items for cart {} (orderId: {}, triggerMsg: {})",
              reservedItemIds.size(),
              cartId,
              correlationId,
              messageId
        );
    }

    @Transactional
    public void confirmReservation(@lombok.NonNull String cartId,
                                   @lombok.NonNull UUID orderId,
                                   @lombok.NonNull String messageId) {

        int updatedRows = reservationRepository.confirmActiveReservation(cartId);

        if (updatedRows >= 1) {
            log.info("Successfully confirmed inventory reservation for cart: {} (order: {})", cartId, orderId);
            outboxWriter.publishInventoryConfirmedEvent(orderId, cartId, messageId);
        } else {
            log.warn("Failed to confirm reservation for cart: {} (order: {}). Reservation expired or missing.", cartId, orderId);
            outboxWriter.publishInventoryConfirmationFailedEvent(
                  orderId,
                  cartId,
                  FAILURE_REASON_EXPIRED,
                  messageId
            );
        }
    }
}
