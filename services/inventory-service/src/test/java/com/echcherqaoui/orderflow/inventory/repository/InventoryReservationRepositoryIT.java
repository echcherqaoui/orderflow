package com.echcherqaoui.orderflow.inventory.repository;

import com.echcherqaoui.orderflow.inventory.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.model.ReservationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.CONFIRMED;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.EXPIRED;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InventoryReservationRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private EntityManager entityManager;

    private String cartId;
    private UUID itemId1;
    private UUID itemId2;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();

        cartId = "cart-" + UUID.randomUUID();
        itemId1 = UUID.randomUUID();
        itemId2 = UUID.randomUUID();
    }

    private InventoryReservation newReservation(String cart, UUID itemId, ReservationStatus status, Instant expiresAt) {
        return new InventoryReservation()
              .setCartId(cart)
              .setItemId(itemId)
              .setStatus(status)
              .setExpiresAt(expiresAt);
    }

    @Test
    @DisplayName("findByCartIdAndStatus returns only matching cartId and status records")
    void findByCartIdAndStatus_matchingCartAndStatus_returnsReservations() {
        reservationRepository.saveAndFlush(newReservation(cartId, itemId1, PENDING, Instant.now().plus(10, ChronoUnit.MINUTES)));
        reservationRepository.saveAndFlush(newReservation(cartId, itemId2, CONFIRMED, Instant.now().plus(10, ChronoUnit.MINUTES)));
        reservationRepository.saveAndFlush(newReservation("cart-other", itemId1, PENDING, Instant.now().plus(10, ChronoUnit.MINUTES)));

        entityManager.clear();

        List<InventoryReservation> results = reservationRepository.findByCartIdAndStatus(cartId, PENDING);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getCartId()).isEqualTo(cartId);
        assertThat(results.getFirst().getItemId()).isEqualTo(itemId1);
        assertThat(results.getFirst().getStatus()).isEqualTo(PENDING);
    }

    @Test
    @DisplayName("findExpiredPendingForUpdate fetches only expired PENDING records bounded by pageable")
    void findExpiredPendingForUpdate_expiredPending_returnsOnlyExpiredPending() {
        Instant pastExpiration = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant futureExpiration = Instant.now().plus(10, ChronoUnit.MINUTES);

        InventoryReservation expiredPending1 = reservationRepository.save(newReservation("cart-1", itemId1, PENDING, pastExpiration));
        InventoryReservation expiredPending2 = reservationRepository.save(newReservation("cart-2", itemId2, PENDING, pastExpiration));

        // Non-matching records (future expiration or non-PENDING status)
        reservationRepository.save(newReservation("cart-3", itemId1, PENDING, futureExpiration));
        reservationRepository.save(newReservation("cart-4", itemId2, CONFIRMED, pastExpiration));

        reservationRepository.flush();
        entityManager.clear();

        List<InventoryReservation> results = reservationRepository.findExpiredPendingForUpdate(
              PENDING,
              Instant.now(),
              PageRequest.of(0, 1)
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getStatus()).isEqualTo(PENDING);
        assertThat(results.getFirst().getExpiresAt()).isBefore(Instant.now());
        assertThat(List.of(expiredPending1.getId(), expiredPending2.getId())).contains(results.getFirst().getId());
    }

    @Test
    @DisplayName("updateExpiresAtByCartId updates expiresAt for specified cartId and status")
    void updateExpiresAtByCartId_matchingCartAndStatus_updatesExpiry() {
        Instant initialExpiry = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        Instant newExpiry = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        InventoryReservation res1 = reservationRepository.save(newReservation(cartId, itemId1, PENDING, initialExpiry));
        InventoryReservation res2 = reservationRepository.save(newReservation(cartId, itemId2, CONFIRMED, initialExpiry));

        reservationRepository.flush();

        reservationRepository.updateExpiresAtByCartId(cartId, newExpiry, PENDING);

        entityManager.clear();

        InventoryReservation reloaded1 = reservationRepository.findById(res1.getId()).orElseThrow();
        InventoryReservation reloaded2 = reservationRepository.findById(res2.getId()).orElseThrow();

        assertThat(reloaded1.getExpiresAt()).isEqualTo(newExpiry);
        assertThat(reloaded2.getExpiresAt()).isEqualTo(initialExpiry);
    }

    @Test
    @DisplayName("updateStatusByIds bulk updates status for given reservation IDs")
    void updateStatusByIds_validIds_updatesStatusInBulk() {
        Instant expiry = Instant.now().plus(10, ChronoUnit.MINUTES);

        InventoryReservation res1 = reservationRepository.save(newReservation("cart-1", itemId1, PENDING, expiry));
        InventoryReservation res2 = reservationRepository.save(newReservation("cart-2", itemId2, PENDING, expiry));
        InventoryReservation res3 = reservationRepository.save(newReservation("cart-3", itemId1, PENDING, expiry));

        reservationRepository.flush();

        List<UUID> targetIds = List.of(res1.getId(), res2.getId());
        reservationRepository.updateStatusByIds(targetIds, EXPIRED);

        entityManager.clear();

        assertThat(reservationRepository.findById(res1.getId()).orElseThrow().getStatus()).isEqualTo(EXPIRED);
        assertThat(reservationRepository.findById(res2.getId()).orElseThrow().getStatus()).isEqualTo(EXPIRED);
        assertThat(reservationRepository.findById(res3.getId()).orElseThrow().getStatus()).isEqualTo(PENDING);
    }
}