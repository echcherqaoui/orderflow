package com.echcherqaoui.orderflow.inventory.scheduler;

import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.model.ReservationStatus;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.EXPIRED;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ReservationSweeperIT implements WithPostgres {

    @Autowired
    private ReservationSweeper reservationSweeper;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Item item1;
    private Item item2;

    private List<InventoryReservation> fetchPending(String cartId) {
        return transactionTemplate.execute(status ->
              reservationRepository.findByCartIdAndStatus(cartId, ReservationStatus.PENDING)
        );
    }

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        item1 = itemRepository.saveAndFlush(newItem(10, 5));
        item2 = itemRepository.saveAndFlush(newItem(10, 8));
    }

    private Item newItem(int total, int remaining) {
        return new Item()
              .setName("test-item-" + UUID.randomUUID())
              .setPriceCents(1000)
              .setTotalEarlyAccessUnits(total)
              .setRemainingUnits(remaining);
    }

    @Test
    @DisplayName("no expired reservations leaves database state untouched")
    void sweep_noExpiredReservations_noChange() {
        reservationRepository.saveAndFlush(new InventoryReservation()
              .setCartId("cart-1")
              .setItemId(item1.getId())
              .setStatus(PENDING)
              .setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES)));

        reservationSweeper.sweep();

        assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(5);

        List<InventoryReservation> pending = fetchPending("cart-1");
        assertThat(pending).hasSize(1);
    }

    @Test
    @DisplayName("expired pending reservations restores item stock and updates status to EXPIRED")
    void sweep_expiredPendingReservations_restoresStockAndMarksExpired() {
        Instant pastExpiration = Instant.now().minus(5, ChronoUnit.MINUTES);

        InventoryReservation expired1 = reservationRepository.save(new InventoryReservation()
              .setCartId("cart-1")
              .setItemId(item1.getId())
              .setStatus(PENDING)
              .setExpiresAt(pastExpiration));

        InventoryReservation expired2 = reservationRepository.save(new InventoryReservation()
              .setCartId("cart-2")
              .setItemId(item1.getId())
              .setStatus(PENDING)
              .setExpiresAt(pastExpiration));

        InventoryReservation expired3 = reservationRepository.save(new InventoryReservation()
              .setCartId("cart-3")
              .setItemId(item2.getId())
              .setStatus(PENDING)
              .setExpiresAt(pastExpiration));

        InventoryReservation active = reservationRepository.save(new InventoryReservation()
              .setCartId("cart-4")
              .setItemId(item2.getId())
              .setStatus(PENDING)
              .setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES)));

        reservationRepository.flush();

        reservationSweeper.sweep();

        assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(7);
        assertThat(itemRepository.findById(item2.getId()).orElseThrow().getRemainingUnits()).isEqualTo(9);

        assertThat(reservationRepository.findById(expired1.getId()).orElseThrow().getStatus()).isEqualTo(EXPIRED);
        assertThat(reservationRepository.findById(expired2.getId()).orElseThrow().getStatus()).isEqualTo(EXPIRED);
        assertThat(reservationRepository.findById(expired3.getId()).orElseThrow().getStatus()).isEqualTo(EXPIRED);
        assertThat(reservationRepository.findById(active.getId()).orElseThrow().getStatus()).isEqualTo(PENDING);
    }

    @Test
    @DisplayName("non-PENDING reservations past expiration are ignored and do not restore stock")
    void sweep_nonPendingExpiredReservations_ignored() {
        Instant pastExpiration = Instant.now().minus(10, ChronoUnit.MINUTES);

        // Already processed or confirmed reservations past expiresAt
        reservationRepository.save(new InventoryReservation()
              .setCartId("cart-1")
              .setItemId(item1.getId())
              .setStatus(ReservationStatus.EXPIRED)
              .setExpiresAt(pastExpiration));

        reservationRepository.flush();

        reservationSweeper.sweep();

        // Stock must remain unchanged at 5
        assertThat(itemRepository.findById(item1.getId()).orElseThrow().getRemainingUnits()).isEqualTo(5);
    }

    @Test
    @DisplayName("more than 500 expired items processes exactly 500 items in single batch")
    void sweep_moreThan500ExpiredItems_respectsBatchSizeLimit() {
        Item bulkItem = itemRepository.saveAndFlush(newItem(600, 5));
        Instant pastExpiration = Instant.now().minus(5, ChronoUnit.MINUTES);

        List<InventoryReservation> reservations = IntStream.range(0, 505)
              .mapToObj(i -> new InventoryReservation()
                    .setCartId("cart-" + i)
                    .setItemId(bulkItem.getId())
                    .setStatus(PENDING)
                    .setExpiresAt(pastExpiration))
              .toList();

        reservationRepository.saveAllAndFlush(reservations);

        reservationSweeper.sweep();

        assertThat(itemRepository.findById(bulkItem.getId()).orElseThrow().getRemainingUnits()).isEqualTo(505);

        long remainingPending = reservationRepository.findAll().stream()
              .filter(r -> r.getStatus() == PENDING)
              .count();

        assertThat(remainingPending).isEqualTo(5);
    }
}