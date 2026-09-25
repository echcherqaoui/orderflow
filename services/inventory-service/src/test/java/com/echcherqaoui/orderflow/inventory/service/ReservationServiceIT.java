package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.exception.domain.InvalidReservationException;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemNotFoundException;
import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.model.ReservationStatus;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.EMPTY_ITEM_LIST;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.ITEMS_NOT_FOUND;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.ITEMS_OUT_OF_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReservationServiceIT implements WithPostgres {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private OutboxWriter outboxWriter;

    private String cartId;
    private Item item1;
    private Item item2;
    private Item item3;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        cartId = "cart-" + UUID.randomUUID();

        item1 = itemRepository.saveAndFlush(newItem(10, 10, 1000));
        item2 = itemRepository.saveAndFlush(newItem(10, 10, 1500));
        item3 = itemRepository.saveAndFlush(newItem(10, 10, 2000));
    }

    private List<InventoryReservation> fetchPending(String cartId) {
        return transactionTemplate.execute(status ->
              reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING)
        );
    }

    private Item newItem(int total, int remaining, long priceCents) {
        return new Item()
              .setName("test-item-" + UUID.randomUUID())
              .setPriceCents(priceCents)
              .setTotalEarlyAccessUnits(total)
              .setRemainingUnits(remaining);
    }

    @Nested
    @DisplayName("reserve(String, Set<UUID>)")
    class Reserve {

        @Test
        @DisplayName("empty requestedItemIds throws InvalidReservationException(EMPTY_ITEM_LIST)")
        void emptyItemSet_throwsInvalidReservationException() {
            Set<UUID> emptySet = Collections.emptySet();

            assertThatThrownBy(() -> reservationService.reserve(cartId, emptySet))
                  .isInstanceOf(InvalidReservationException.class)
                  .hasMessageContaining(EMPTY_ITEM_LIST.getMessage());

            assertThat(reservationRepository.count()).isZero();
        }

        @Test
        @DisplayName("successful reservation decrements stock, creates PENDING reservations, and returns total price")
        void reserve_successfulNewReservation() {
            Set<UUID> requestedItemIds = Set.of(item1.getId(), item2.getId());

            long totalPrice = reservationService.reserve(cartId, requestedItemIds);

            assertThat(totalPrice).isEqualTo(2500L);

            Item reloaded1 = itemRepository.findById(item1.getId()).orElseThrow();
            Item reloaded2 = itemRepository.findById(item2.getId()).orElseThrow();
            Item reloaded3 = itemRepository.findById(item3.getId()).orElseThrow();

            assertThat(reloaded1.getRemainingUnits()).isEqualTo(9);
            assertThat(reloaded2.getRemainingUnits()).isEqualTo(9);
            assertThat(reloaded3.getRemainingUnits()).isEqualTo(10);

            List<InventoryReservation> reservations = fetchPending(cartId);

            assertThat(reservations).hasSize(2);
            assertThat(reservations)
                  .extracting(InventoryReservation::getItemId)
                  .containsExactlyInAnyOrder(item1.getId(), item2.getId());

            assertThat(reservations).allSatisfy(r -> {
                assertThat(r.getStatus()).isEqualTo(ReservationStatus.PENDING);
                assertThat(r.getExpiresAt()).isAfter(Instant.now());
            });
        }

        @Test
        @DisplayName("insufficient stock on any item throws OutOfStockException and rolls back transaction")
        void reserve_outOfStock_throwsExceptionAndRollsBack() {
            Item outOfStockItem = itemRepository.saveAndFlush(newItem(10, 0, 500));
            Set<UUID> requestedItemIds = Set.of(item1.getId(), outOfStockItem.getId());

            assertThatThrownBy(() -> reservationService.reserve(cartId, requestedItemIds))
                  .isInstanceOf(OutOfStockException.class)
                  .hasMessageContaining(ITEMS_OUT_OF_STOCK.getMessage());

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(reservationRepository.count()).isZero();
        }

        @Test
        @DisplayName("requesting non-existent item UUID throws ItemNotFoundException and rolls back transaction")
        void reserve_nonExistentItemId_throwsExceptionAndRollsBack() {
            Set<UUID> requestedItemIds = Set.of(item1.getId(), UUID.randomUUID());

            assertThatThrownBy(() -> reservationService.reserve(cartId, requestedItemIds))
                  .isInstanceOf(ItemNotFoundException.class)
                  .hasMessageContaining(ITEMS_NOT_FOUND.getMessage());

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(reservationRepository.count()).isZero();
        }

        @Test
        @DisplayName("subsequent reservation with identical items extends TTL and returns total price without double-decrementing stock")
        void reserve_idempotentReplay_extendsTtlOnly() {
            Set<UUID> requestedItemIds = Set.of(item1.getId(), item2.getId());

            long initialPrice = reservationService.reserve(cartId, requestedItemIds);
            assertThat(initialPrice).isEqualTo(2500L);

            List<InventoryReservation> initialReservations = fetchPending(cartId);

            Instant initialExpiry = initialReservations.getFirst().getExpiresAt();

            long replayPrice = reservationService.reserve(cartId, requestedItemIds);
            assertThat(replayPrice).isEqualTo(2500L);

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> updatedReservations = fetchPending(cartId);

            assertThat(updatedReservations).hasSize(2);
            assertThat(updatedReservations.getFirst().getExpiresAt()).isAfterOrEqualTo(initialExpiry);
        }

        @Test
        @DisplayName("changing item set releases previous reservations, restores old stock, and reserves new set returning new price")
        void reserve_cancelAndReplace_completeSetChange_success() {
            long price1 = reservationService.reserve(cartId, Set.of(item1.getId()));
            assertThat(price1).isEqualTo(1000L);
            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            long price2 = reservationService.reserve(cartId, Set.of(item2.getId()));
            assertThat(price2).isEqualTo(1500L);

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> reservations = fetchPending(cartId);

            assertThat(reservations).hasSize(1);
            assertThat(reservations.getFirst().getItemId()).isEqualTo(item2.getId());
        }

        @Test
        @DisplayName("partial item set change correctly adjusts stock for removed, retained, and added items and calculates total price")
        void reserve_cancelAndReplace_partialOverlap_success() {
            long price1 = reservationService.reserve(cartId, Set.of(item1.getId(), item2.getId()));
            assertThat(price1).isEqualTo(2500L);

            long price2 = reservationService.reserve(cartId, Set.of(item2.getId(), item3.getId()));
            assertThat(price2).isEqualTo(3500L);

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);
            assertThat(itemRepository.findById(item3.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> reservations = fetchPending(cartId);

            assertThat(reservations).extracting(InventoryReservation::getItemId)
                  .containsExactlyInAnyOrder(item2.getId(), item3.getId());
        }

        @Test
        @DisplayName("failed replacement due to out-of-stock rolls back release of original items")
        void reserve_cancelAndReplace_outOfStockNewItem_rollsBackEntirely() {
            reservationService.reserve(cartId, Set.of(item1.getId()));

            Item outOfStockItem = itemRepository.saveAndFlush(newItem(10, 0, 500));
            Set<UUID> outOfStockItemIds = Set.of(outOfStockItem.getId());

            assertThatThrownBy(() -> reservationService.reserve(cartId, outOfStockItemIds))
                  .isInstanceOf(OutOfStockException.class);

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> reservations = fetchPending(cartId);

            assertThat(reservations).hasSize(1);

            assertThat(reservations.getFirst().getItemId()).isEqualTo(item1.getId());
        }
    }

    @Nested
    @DisplayName("extendReservation(...)")
    class ExtendReservation {

        @Test
        @DisplayName("successful extension updates reservation expiry, attaches orderId, and publishes event")
        void extendReservation_success() {
            reservationService.reserve(cartId, Set.of(item1.getId()));

            String  orderId = UUID.randomUUID().toString();
            String correlationId = orderId;
            String messageId = "msg-123";

            reservationService.extendReservation(cartId, orderId, correlationId, messageId);

            List<InventoryReservation> reservations = fetchPending(cartId);

            assertThat(reservations).hasSize(1);
            assertThat(reservations.getFirst().getOrderId()).isEqualTo(UUID.fromString(orderId));

            verify(outboxWriter).publishReservationExtendedEvent(
                  eq(UUID.fromString(orderId)),
                  eq(messageId),
                  eq(cartId),
                  any(Instant.class)
            );
        }

        @Test
        @DisplayName("missing or expired reservation triggers failure outbox event")
        void extendReservation_missingReservation_publishesFailureEvent() {
            String nonExistentCartId = "cart-non-existent";
            String orderId = UUID.randomUUID().toString();

            reservationService.extendReservation(nonExistentCartId, orderId, orderId, "msg-123");

            verify(outboxWriter).publishReservationExtensionFailedEvent(
                  UUID.fromString(orderId),
                  "msg-123",
                  nonExistentCartId,
                  "RESERVATION_EXPIRED"
            );
        }
    }

    @Nested
    @DisplayName("releaseReservation(...)")
    class ReleaseReservation {

        @Test
        @DisplayName("releasing active reservation restores item stock, deletes reservation records, and publishes event")
        void releaseReservation_activeReservation_restoresStockAndDeletes() {
            reservationService.reserve(cartId, Set.of(item1.getId(), item2.getId()));

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            String correlationId = UUID.randomUUID().toString();
            String messageId = "msg-456";

            reservationService.releaseReservation(cartId, correlationId, messageId);

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);

            verify(outboxWriter).publishInventoryReleasedEvent(
                  UUID.fromString(correlationId),
                  messageId,
                  cartId
            );
        }

        @Test
        @DisplayName("releasing non-existent reservation is idempotent and publishes event without DB changes")
        void releaseReservation_noActiveReservation_publishesEventOnly() {
            String correlationId = UUID.randomUUID().toString();
            String messageId = "msg-789";

            reservationService.releaseReservation("cart-non-existent", correlationId, messageId);

            verify(outboxWriter).publishInventoryReleasedEvent(
                  UUID.fromString(correlationId),
                  messageId,
                  "cart-non-existent"
            );
        }
    }

    @Nested
    @DisplayName("confirmReservation(String, UUID, String)")
    class ConfirmReservation {

        @Test
        @DisplayName("confirming active reservation updates status to CONFIRMED and publishes confirmed outbox event")
        void confirmReservation_activeReservation_confirmsAndPublishesEvent() {
            reservationService.reserve(cartId, Set.of(item1.getId(), item2.getId()));

            UUID orderUuid = UUID.randomUUID();
            String messageId = "msg-confirm-123";

            reservationService.confirmReservation(cartId, orderUuid, messageId);

            List<InventoryReservation> confirmedReservations = transactionTemplate.execute(status ->
                  reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.CONFIRMED)
            );

            assertThat(confirmedReservations).hasSize(2);
            assertThat(fetchPending(cartId)).isEmpty();

            verify(outboxWriter).publishInventoryConfirmedEvent(
                  orderUuid,
                  cartId,
                  messageId
            );
        }

        @Test
        @DisplayName("confirming missing or expired reservation publishes confirmation failed outbox event")
        void confirmReservation_missingOrExpiredReservation_publishesFailureEvent() {
            String nonExistentCartId = "cart-non-existent";
            UUID orderUuid = UUID.randomUUID();
            String messageId = "msg-confirm-456";

            reservationService.confirmReservation(nonExistentCartId, orderUuid, messageId);

            verify(outboxWriter).publishInventoryConfirmationFailedEvent(
                  orderUuid,
                  nonExistentCartId,
                  "RESERVATION_EXPIRED",
                  messageId
            );
        }
    }
}