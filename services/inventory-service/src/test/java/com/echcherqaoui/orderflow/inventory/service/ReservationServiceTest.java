package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.exception.domain.InvalidReservationException;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemNotFoundException;
import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.projection.ItemSummaryDto;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.CONCURRENT_RESERVATION_ATTEMPT;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.EMPTY_ITEM_LIST;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.ITEMS_NOT_FOUND;
import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.ITEMS_OUT_OF_STOCK;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private InventoryReservationRepository reservationRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private ReservationService reservationService;

    @Captor
    private ArgumentCaptor<List<InventoryReservation>> reservationsCaptor;

    private static final String CART_ID = "cart-123";
    private static final String ORDER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CORRELATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MESSAGE_ID = "msg-999";

    private static final UUID ITEM_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ITEM_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long TOTAL_PRICE_CENTS = 5000L;

    private ItemSummaryDto itemSummary(long count, long priceCents) {
        return new ItemSummaryDto() {
            @Override
            public Long getCount() {
                return count;
            }

            @Override
            public Long getTotalPriceCents() {
                return priceCents;
            }
        };
    }

    @Nested
    @DisplayName("reserve(String, Set<UUID>)")
    class ReserveMethod {

        @Test
        @DisplayName("Should create new reservations and return total price when stock is available")
        void reserve_NewCart_Success() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);

            when(itemRepository.getItemSummary(requestedItems))
                  .thenReturn(itemSummary(2, TOTAL_PRICE_CENTS));
            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING))
                  .thenReturn(Collections.emptyList());
            when(itemRepository.decrementStockBatch(requestedItems))
                  .thenReturn(2);

            long totalPrice = reservationService.reserve(CART_ID, requestedItems);

            assertThat(totalPrice).isEqualTo(TOTAL_PRICE_CENTS);
            verify(itemRepository).decrementStockBatch(requestedItems);
            verify(reservationRepository).saveAllAndFlush(reservationsCaptor.capture());

            List<InventoryReservation> savedReservations = reservationsCaptor.getValue();
            assertThat(savedReservations).hasSize(2);
            assertThat(savedReservations).extracting(InventoryReservation::getCartId).containsOnly(CART_ID);
            assertThat(savedReservations).extracting(InventoryReservation::getItemId)
                  .containsExactlyInAnyOrder(ITEM_ID_1, ITEM_ID_2);
            assertThat(savedReservations).extracting(InventoryReservation::getStatus).containsOnly(PENDING);
            assertThat(savedReservations).allSatisfy(res ->
                  assertThat(res.getExpiresAt()).isAfter(Instant.now())
            );
        }

        @Test
        @DisplayName("Should throw InvalidReservationException when requested item set is empty")
        void reserve_EmptyRequestedItems_ThrowsException() {
            Set<UUID> collections = Collections.emptySet();

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, collections))
                  .isInstanceOf(InvalidReservationException.class)
                  .extracting("errorCode").isEqualTo(EMPTY_ITEM_LIST);

            verify(itemRepository, never()).getItemSummary(any());
            verify(reservationRepository, never()).findByCartIdAndStatus(any(), any());
        }

        @Test
        @DisplayName("Should throw ItemNotFoundException when item summary count mismatches requested size")
        void reserve_ItemSummaryCountMismatch_ThrowsException() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);

            when(itemRepository.getItemSummary(requestedItems))
                  .thenReturn(itemSummary(1, 2500L));

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, requestedItems))
                  .isInstanceOf(ItemNotFoundException.class)
                  .extracting("errorCode").isEqualTo(ITEMS_NOT_FOUND);

            verify(reservationRepository, never()).findByCartIdAndStatus(any(), any());
            verify(itemRepository, never()).decrementStockBatch(any());
        }

        @Test
        @DisplayName("Should throw ItemNotFoundException when item summary query returns null")
        void reserve_ItemSummaryNull_ThrowsException() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);

            when(itemRepository.getItemSummary(requestedItems)).thenReturn(null);

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, requestedItems))
                  .isInstanceOf(ItemNotFoundException.class)
                  .extracting("errorCode").isEqualTo(ITEMS_NOT_FOUND);

            verify(itemRepository, never()).decrementStockBatch(any());
        }

        @Test
        @DisplayName("Should throw OutOfStockException when stock decrement count is less than requested size")
        void reserve_InsufficientStock_ThrowsException() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);

            when(itemRepository.getItemSummary(requestedItems))
                  .thenReturn(itemSummary(2, TOTAL_PRICE_CENTS));
            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING))
                  .thenReturn(Collections.emptyList());
            when(itemRepository.decrementStockBatch(requestedItems))
                  .thenReturn(1);

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, requestedItems))
                  .isInstanceOf(OutOfStockException.class)
                  .extracting("errorCode").isEqualTo(ITEMS_OUT_OF_STOCK);

            verify(reservationRepository, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("Should extend TTL idempotently when exact same items are requested")
        void reserve_IdempotentReplay_ExtendsTtlOnly() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);
            List<InventoryReservation> existing = List.of(
                  new InventoryReservation().setId(UUID.randomUUID()).setCartId(CART_ID).setItemId(ITEM_ID_1).setStatus(PENDING),
                  new InventoryReservation().setId(UUID.randomUUID()).setCartId(CART_ID).setItemId(ITEM_ID_2).setStatus(PENDING)
            );

            when(itemRepository.getItemSummary(requestedItems))
                  .thenReturn(itemSummary(2, TOTAL_PRICE_CENTS));
            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(existing);

            long totalPrice = reservationService.reserve(CART_ID, requestedItems);

            assertThat(totalPrice).isEqualTo(TOTAL_PRICE_CENTS);
            verify(reservationRepository).updateExpiresAtByCartId(eq(CART_ID), any(Instant.class), eq(PENDING));
            verify(itemRepository, never()).decrementStockBatch(any());
            verify(itemRepository, never()).incrementStockBatch(any());
            verify(reservationRepository, never()).deleteAllByIdInBatch(any());
            verify(reservationRepository, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("Should release existing reservation and apply new one when item list changes")
        void reserve_CancelAndReplace_Success() {
            UUID oldResId = UUID.randomUUID();
            Set<UUID> oldItems = Set.of(ITEM_ID_1);
            Set<UUID> newItems = Set.of(ITEM_ID_2);

            InventoryReservation existing = new InventoryReservation()
                  .setId(oldResId)
                  .setCartId(CART_ID)
                  .setItemId(ITEM_ID_1)
                  .setStatus(PENDING);

            when(itemRepository.getItemSummary(newItems))
                  .thenReturn(itemSummary(1, 3000L));
            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(List.of(existing));
            when(itemRepository.decrementStockBatch(newItems)).thenReturn(1);

            long totalPrice = reservationService.reserve(CART_ID, newItems);

            assertThat(totalPrice).isEqualTo(3000L);
            verify(itemRepository).incrementStockBatch(oldItems);
            verify(reservationRepository).deleteAllByIdInBatch(Set.of(oldResId));
            verify(itemRepository).decrementStockBatch(newItems);
            verify(reservationRepository).saveAllAndFlush(reservationsCaptor.capture());

            assertThat(reservationsCaptor.getValue()).extracting(InventoryReservation::getItemId).containsExactly(ITEM_ID_2);
        }

        @Test
        @DisplayName("Should throw InvalidReservationException when concurrent reservation fails DB constraint")
        void reserve_ConcurrentReservationAttempt_ThrowsException() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1);

            when(itemRepository.getItemSummary(requestedItems))
                  .thenReturn(itemSummary(1, 2500L));
            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING))
                  .thenReturn(Collections.emptyList());
            when(itemRepository.decrementStockBatch(requestedItems))
                  .thenReturn(1);
            when(reservationRepository.saveAllAndFlush(any()))
                  .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, requestedItems))
                  .isInstanceOf(InvalidReservationException.class)
                  .extracting("errorCode").isEqualTo(CONCURRENT_RESERVATION_ATTEMPT);
        }
    }

    @Nested
    @DisplayName("extendReservation(...)")
    class ExtendReservationMethod {

        @Test
        @DisplayName("Should extend reservation and publish extended event when query updates records")
        void extendReservation_Success() {
            when(reservationRepository.extendReservationAndSetOrderId(any(Instant.class), eq(UUID.fromString(ORDER_ID)), eq(CART_ID), any(Instant.class)))
                  .thenReturn(1);

            reservationService.extendReservation(CART_ID, ORDER_ID, CORRELATION_ID, MESSAGE_ID);

            verify(outboxWriter).publishReservationExtendedEvent(
                  eq(UUID.fromString(ORDER_ID)),
                  eq(MESSAGE_ID),
                  eq(CART_ID),
                  any(Instant.class)
            );
            verify(outboxWriter, never()).publishReservationExtensionFailedEvent(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should publish failure event when no reservation records are updated")
        void extendReservation_ExpiredOrMissing_PublishesFailure() {
            when(reservationRepository.extendReservationAndSetOrderId(any(Instant.class), eq(UUID.fromString(ORDER_ID)), eq(CART_ID), any(Instant.class)))
                  .thenReturn(0);

            reservationService.extendReservation(CART_ID, ORDER_ID, CORRELATION_ID, MESSAGE_ID);

            verify(outboxWriter).publishReservationExtensionFailedEvent(
                  UUID.fromString(ORDER_ID),
                  MESSAGE_ID,
                  CART_ID,
                  "RESERVATION_EXPIRED"
            );
            verify(outboxWriter, never()).publishReservationExtendedEvent(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("releaseReservation(...)")
    class ReleaseReservationMethod {

        @Test
        @DisplayName("Should restore stock, delete reservations, and publish released event when active reservations exist")
        void releaseReservation_ActiveReservationsExist_Success() {
            UUID resId1 = UUID.randomUUID();
            UUID resId2 = UUID.randomUUID();
            List<InventoryReservation> existing = List.of(
                  new InventoryReservation().setId(resId1).setCartId(CART_ID).setItemId(ITEM_ID_1).setStatus(PENDING),
                  new InventoryReservation().setId(resId2).setCartId(CART_ID).setItemId(ITEM_ID_2).setStatus(PENDING)
            );

            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(existing);

            reservationService.releaseReservation(CART_ID, CORRELATION_ID, MESSAGE_ID);

            verify(itemRepository).incrementStockBatch(Set.of(ITEM_ID_1, ITEM_ID_2));
            verify(reservationRepository).deleteAllByIdInBatch(Set.of(resId1, resId2));
            verify(outboxWriter).publishInventoryReleasedEvent(
                  UUID.fromString(CORRELATION_ID),
                  MESSAGE_ID,
                  CART_ID
            );
        }

        @Test
        @DisplayName("Should publish released event without modifying database when no active reservations are found")
        void releaseReservation_NoActiveReservations_PublishesEventOnly() {
            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(Collections.emptyList());

            reservationService.releaseReservation(CART_ID, CORRELATION_ID, MESSAGE_ID);

            verify(itemRepository, never()).incrementStockBatch(any());
            verify(reservationRepository, never()).deleteAllByIdInBatch(any());
            verify(outboxWriter).publishInventoryReleasedEvent(
                  UUID.fromString(CORRELATION_ID),
                  MESSAGE_ID,
                  CART_ID
            );
        }
    }
}