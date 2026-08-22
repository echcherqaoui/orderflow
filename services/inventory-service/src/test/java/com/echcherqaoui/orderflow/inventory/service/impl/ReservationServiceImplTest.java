package com.echcherqaoui.orderflow.inventory.service.impl;

import com.echcherqaoui.orderflow.inventory.exception.domain.InvalidReservationException;
import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock
    private InventoryReservationRepository reservationRepository;

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    @Captor
    private ArgumentCaptor<List<InventoryReservation>> reservationsCaptor;

    private static final String CART_ID = "cart-123";
    private static final UUID ITEM_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ITEM_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ITEM_ID_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Nested
    @DisplayName("reserve(String, Set<UUID>)")
    class ReserveMethod {

        @Test
        @DisplayName("Should create new reservations successfully when stock is available and no prior reservation exists")
        void reserve_NewCart_Success() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);

            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING))
                  .thenReturn(Collections.emptyList());

            when(itemRepository.decrementStockBatch(requestedItems))
                  .thenReturn(2);

            reservationService.reserve(CART_ID, requestedItems);

            verify(itemRepository).decrementStockBatch(requestedItems);
            verify(reservationRepository).saveAll(reservationsCaptor.capture());

            List<InventoryReservation> savedReservations = reservationsCaptor.getValue();
            assertThat(savedReservations).hasSize(2);
            assertThat(savedReservations).extracting(InventoryReservation::getCartId).containsOnly(CART_ID);
            assertThat(savedReservations).extracting(InventoryReservation::getItemId).containsExactlyInAnyOrder(ITEM_ID_1, ITEM_ID_2);
            assertThat(savedReservations).extracting(InventoryReservation::getStatus).containsOnly(PENDING);
            assertThat(savedReservations).allSatisfy(res ->
                  assertThat(res.getExpiresAt()).isAfter(Instant.now())
            );
        }

        @Test
        @DisplayName("Should throw OutOfStockException when stock decrement count is less than requested size")
        void reserve_NewCart_InsufficientStock_ThrowsException() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);

            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING))
                  .thenReturn(Collections.emptyList());
            when(itemRepository.decrementStockBatch(requestedItems))
                  .thenReturn(1);

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, requestedItems))
                  .isInstanceOf(OutOfStockException.class);

            verify(reservationRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Should extend TTL idempotently with DEFAULT_TTL_SECONDS when exact same items are requested")
        void reserve_IdempotentReplay_ExtendsTtlOnly() {
            Set<UUID> requestedItems = Set.of(ITEM_ID_1, ITEM_ID_2);
            List<InventoryReservation> existing = List.of(
                  new InventoryReservation().setId(UUID.randomUUID()).setCartId(CART_ID).setItemId(ITEM_ID_1).setStatus(PENDING),
                  new InventoryReservation().setId(UUID.randomUUID()).setCartId(CART_ID).setItemId(ITEM_ID_2).setStatus(PENDING)
            );

            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(existing);

            reservationService.reserve(CART_ID, requestedItems);

            verify(reservationRepository).updateExpiresAtByCartId(eq(CART_ID), any(Instant.class), eq(PENDING));
            verify(itemRepository, never()).decrementStockBatch(any());
            verify(itemRepository, never()).incrementStockBatch(any());
            verify(reservationRepository, never()).deleteAllByIdInBatch(any());
            verify(reservationRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Should release existing reservation and apply new one when item list completely changes")
        void reserve_CancelAndReplace_CompleteChange_Success() {
            UUID oldResId = UUID.randomUUID();
            Set<UUID> oldItems = Set.of(ITEM_ID_1);
            Set<UUID> newItems = Set.of(ITEM_ID_2);

            InventoryReservation existing = new InventoryReservation()
                  .setId(oldResId)
                  .setCartId(CART_ID)
                  .setItemId(ITEM_ID_1)
                  .setStatus(PENDING);

            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(List.of(existing));
            when(itemRepository.decrementStockBatch(newItems)).thenReturn(1);

            reservationService.reserve(CART_ID, newItems);

            verify(itemRepository).incrementStockBatch(oldItems);
            verify(reservationRepository).deleteAllByIdInBatch(Set.of(oldResId));
            verify(itemRepository).decrementStockBatch(newItems);
            verify(reservationRepository).saveAll(reservationsCaptor.capture());

            assertThat(reservationsCaptor.getValue()).extracting(InventoryReservation::getItemId).containsExactly(ITEM_ID_2);
        }

        @Test
        @DisplayName("Should treat partial item overlap as non-equal and trigger full cancel-and-replace")
        void reserve_CancelAndReplace_PartialOverlap_Success() {
            UUID oldResId1 = UUID.randomUUID();
            UUID oldResId2 = UUID.randomUUID();
            Set<UUID> oldItems = Set.of(ITEM_ID_1, ITEM_ID_2);
            Set<UUID> newItems = Set.of(ITEM_ID_2, ITEM_ID_3);

            List<InventoryReservation> existing = List.of(
                  new InventoryReservation().setId(oldResId1).setCartId(CART_ID).setItemId(ITEM_ID_1).setStatus(PENDING),
                  new InventoryReservation().setId(oldResId2).setCartId(CART_ID).setItemId(ITEM_ID_2).setStatus(PENDING)
            );

            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(existing);
            when(itemRepository.decrementStockBatch(newItems)).thenReturn(2);

            reservationService.reserve(CART_ID, newItems);

            verify(itemRepository).incrementStockBatch(oldItems);
            verify(reservationRepository).deleteAllByIdInBatch(Set.of(oldResId1, oldResId2));
            verify(itemRepository).decrementStockBatch(newItems);
            verify(reservationRepository).saveAll(any());
        }

        @Test
        @DisplayName("Should release old items but throw OutOfStockException if new stock decrement fails during cancel-and-replace")
        void reserve_CancelAndReplace_NewStockFails_ThrowsException() {
            UUID oldResId = UUID.randomUUID();
            Set<UUID> oldItems = Set.of(ITEM_ID_1);
            Set<UUID> newItems = Set.of(ITEM_ID_2);

            InventoryReservation existing = new InventoryReservation()
                  .setId(oldResId)
                  .setCartId(CART_ID)
                  .setItemId(ITEM_ID_1)
                  .setStatus(PENDING);

            when(reservationRepository.findByCartIdAndStatus(CART_ID, PENDING)).thenReturn(List.of(existing));
            when(itemRepository.decrementStockBatch(newItems)).thenReturn(0);

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, newItems))
                  .isInstanceOf(OutOfStockException.class);

            verify(itemRepository).incrementStockBatch(oldItems);
            verify(reservationRepository).deleteAllByIdInBatch(Set.of(oldResId));
            verify(reservationRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Should throw InvalidReservationException when requested item set is empty")
        void reserve_EmptyRequestedItems_ThrowsException() {
            Set<UUID> emptySet = Collections.emptySet();

            assertThatThrownBy(() -> reservationService.reserve(CART_ID, emptySet))
                  .isInstanceOf(InvalidReservationException.class);

            verify(reservationRepository, never()).findByCartIdAndStatus(any(), any());
            verify(itemRepository, never()).decrementStockBatch(any());
            verify(reservationRepository, never()).saveAll(any());
        }
    }
}