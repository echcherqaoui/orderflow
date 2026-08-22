package com.echcherqaoui.orderflow.inventory.service.impl;

import com.echcherqaoui.orderflow.inventory.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.inventory.exception.domain.InvalidReservationException;
import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.model.ReservationStatus;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.echcherqaoui.orderflow.inventory.exception.enums.InventoryErrorCode.EMPTY_ITEM_LIST;
import static com.echcherqaoui.orderflow.inventory.exception.enums.InventoryErrorCode.ITEMS_OUT_OF_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReservationServiceImplIT extends AbstractIntegrationTest {

    @Autowired
    private ReservationServiceImpl reservationService;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private ItemRepository itemRepository;

    private String cartId;
    private Item item1;
    private Item item2;
    private Item item3;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        cartId = "cart-" + UUID.randomUUID();

        item1 = itemRepository.saveAndFlush(newItem(10, 10));
        item2 = itemRepository.saveAndFlush(newItem(10, 10));
        item3 = itemRepository.saveAndFlush(newItem(10, 10));
    }

    private Item newItem(int total, int remaining) {
        return new Item()
              .setName("test-item-" + UUID.randomUUID())
              .setPriceCents(1000)
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
        @DisplayName("successful reservation decrements stock and creates PENDING reservation rows")
        void reserve_successfulNewReservation() {
            Set<UUID> requestedItemIds = Set.of(item1.getId(), item2.getId());

            reservationService.reserve(cartId, requestedItemIds);

            Item reloaded1 = itemRepository.findById(item1.getId()).orElseThrow();
            Item reloaded2 = itemRepository.findById(item2.getId()).orElseThrow();
            Item reloaded3 = itemRepository.findById(item3.getId()).orElseThrow();

            assertThat(reloaded1.getRemainingUnits()).isEqualTo(9);
            assertThat(reloaded2.getRemainingUnits()).isEqualTo(9);
            assertThat(reloaded3.getRemainingUnits()).isEqualTo(10);

            List<InventoryReservation> reservations = reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING);

            assertThat(reservations)
                  .hasSize(2);

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
            Item outOfStockItem = itemRepository.saveAndFlush(newItem(10, 0));
            Set<UUID> requestedItemIds = Set.of(item1.getId(), outOfStockItem.getId());

            assertThatThrownBy(() -> reservationService.reserve(cartId, requestedItemIds))
                  .isInstanceOf(OutOfStockException.class)
                  .hasMessageContaining(ITEMS_OUT_OF_STOCK.getMessage());

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(reservationRepository.count()).isZero();
        }

        @Test
        @DisplayName("requesting non-existent item UUID throws OutOfStockException and rolls back transaction")
        void reserve_nonExistentItemId_throwsExceptionAndRollsBack() {
            Set<UUID> requestedItemIds = Set.of(item1.getId(), UUID.randomUUID());

            assertThatThrownBy(() -> reservationService.reserve(cartId, requestedItemIds))
                  .isInstanceOf(OutOfStockException.class)
                  .hasMessageContaining(ITEMS_OUT_OF_STOCK.getMessage());

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(reservationRepository.count()).isZero();
        }

        @Test
        @DisplayName("subsequent reservation with identical items extends TTL without double-decrementing stock")
        void reserve_idempotentReplay_extendsTtlOnly() {
            Set<UUID> requestedItemIds = Set.of(item1.getId(), item2.getId());

            reservationService.reserve(cartId, requestedItemIds);

            List<InventoryReservation> initialReservations = reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING);
            Instant initialExpiry = initialReservations.getFirst().getExpiresAt();

            reservationService.reserve(cartId, requestedItemIds);

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> updatedReservations = reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING);

            assertThat(updatedReservations).hasSize(2);
            assertThat(updatedReservations.getFirst().getExpiresAt()).isAfterOrEqualTo(initialExpiry);
        }

        @Test
        @DisplayName("changing item set releases previous reservations, restores old stock, and reserves new set")
        void reserve_cancelAndReplace_completeSetChange_success() {
            reservationService.reserve(cartId, Set.of(item1.getId()));

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            reservationService.reserve(cartId, Set.of(item2.getId()));

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> reservations = reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING);

            assertThat(reservations).hasSize(1);
            assertThat(reservations.getFirst().getItemId()).isEqualTo(item2.getId());
        }

        @Test
        @DisplayName("partial item set change correctly adjusts stock for removed, retained, and added items")
        void reserve_cancelAndReplace_partialOverlap_success() {
            reservationService.reserve(cartId, Set.of(item1.getId(), item2.getId()));

            reservationService.reserve(cartId, Set.of(item2.getId(), item3.getId()));

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(10);
            assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);
            assertThat(itemRepository.findById(item3.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> reservations = reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING);

            assertThat(reservations).extracting(InventoryReservation::getItemId)
                  .containsExactlyInAnyOrder(item2.getId(), item3.getId());
        }

        @Test
        @DisplayName("failed replacement due to out-of-stock rolls back release of original items")
        void reserve_cancelAndReplace_outOfStockNewItem_rollsBackEntirely() {
            reservationService.reserve(cartId, Set.of(item1.getId()));

            Item outOfStockItem = itemRepository.saveAndFlush(newItem(10, 0));
            Set<UUID> outOfStockItemIds = Set.of(outOfStockItem.getId());

            assertThatThrownBy(() -> reservationService.reserve(cartId, outOfStockItemIds))
                  .isInstanceOf(OutOfStockException.class);

            assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

            List<InventoryReservation> reservations = reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING);
            assertThat(reservations).hasSize(1);
            assertThat(reservations.getFirst().getItemId()).isEqualTo(item1.getId());
        }
    }
}