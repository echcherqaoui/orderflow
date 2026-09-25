package com.echcherqaoui.orderflow.inventory.repository;

import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.model.ReservationStatus;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

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
@ActiveProfiles("test")
class InventoryReservationRepositoryIT implements WithPostgres {

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

    @Nested
    @DisplayName("findByCartIdAndStatus")
    class FindByCartIdAndStatusTests {

        @Test
        @DisplayName("Returns only matching cartId and status records")
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
    }

    @Nested
    @DisplayName("findExpiredPendingForUpdate")
    class FindExpiredPendingForUpdateTests {

        @Test
        @DisplayName("Fetches only expired PENDING records bounded by pageable")
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
    }

    @Nested
    @DisplayName("updateExpiresAtByCartId")
    class UpdateExpiresAtByCartIdTests {

        @Test
        @DisplayName("Updates expiresAt for specified cartId and status")
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
    }

    @Nested
    @DisplayName("updateStatusByIds")
    class UpdateStatusByIdsTests {

        @Test
        @DisplayName("Bulk updates status for given reservation IDs")
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

    @Nested
    @DisplayName("extendReservationAndSetOrderId")
    class ExtendReservationAndSetOrderIdTests {

        @Test
        @DisplayName("Updates expiry and attaches orderId for active PENDING reservations")
        void extendReservationAndSetOrderId_activePending_updatesExpiryAndOrderId() {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Instant currentExpiry = now.plus(10, ChronoUnit.MINUTES);
            Instant newExpiry = now.plus(30, ChronoUnit.MINUTES);
            UUID orderId = UUID.randomUUID();
            UUID itemId3 = UUID.randomUUID();

            InventoryReservation res1 = reservationRepository.save(newReservation(cartId, itemId1, PENDING, currentExpiry));
            InventoryReservation res2 = reservationRepository.save(newReservation(cartId, itemId2, PENDING, currentExpiry));

            // Non-matching records: expired PENDING, non-PENDING status, or different cart
            InventoryReservation expiredRes = reservationRepository.save(newReservation(cartId, itemId3, PENDING, now.minus(5, ChronoUnit.MINUTES)));
            InventoryReservation confirmedRes = reservationRepository.save(newReservation(cartId, itemId2, CONFIRMED, currentExpiry));
            InventoryReservation otherCartRes = reservationRepository.save(newReservation("other-cart", itemId1, PENDING, currentExpiry));

            reservationRepository.flush();

            int updatedCount = reservationRepository.extendReservationAndSetOrderId(newExpiry, orderId, cartId, now);

            assertThat(updatedCount).isEqualTo(2);

            entityManager.clear();

            InventoryReservation reloaded1 = reservationRepository.findById(res1.getId()).orElseThrow();
            InventoryReservation reloaded2 = reservationRepository.findById(res2.getId()).orElseThrow();
            InventoryReservation reloadedExpired = reservationRepository.findById(expiredRes.getId()).orElseThrow();
            InventoryReservation reloadedConfirmed = reservationRepository.findById(confirmedRes.getId()).orElseThrow();
            InventoryReservation reloadedOtherCart = reservationRepository.findById(otherCartRes.getId()).orElseThrow();

            assertThat(reloaded1.getExpiresAt()).isEqualTo(newExpiry);
            assertThat(reloaded1.getOrderId()).isEqualTo(orderId);

            assertThat(reloaded2.getExpiresAt()).isEqualTo(newExpiry);
            assertThat(reloaded2.getOrderId()).isEqualTo(orderId);

            assertThat(reloadedExpired.getOrderId()).isNull();
            assertThat(reloadedConfirmed.getOrderId()).isNull();
            assertThat(reloadedOtherCart.getOrderId()).isNull();
        }

        @Test
        @DisplayName("Returns 0 when no active matching reservations exist")
        void extendReservationAndSetOrderId_noMatchingActiveReservation_returnsZero() {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Instant newExpiry = now.plus(30, ChronoUnit.MINUTES);
            UUID orderId = UUID.randomUUID();

            reservationRepository.save(newReservation(cartId, itemId1, PENDING, now.minus(1, ChronoUnit.MINUTES)));
            reservationRepository.flush();

            int updatedCount = reservationRepository.extendReservationAndSetOrderId(newExpiry, orderId, cartId, now);

            assertThat(updatedCount).isZero();
        }
    }

    @Nested
    @DisplayName("confirmActiveReservation")
    class ConfirmActiveReservationTests {

        @Test
        @DisplayName("Updates status to CONFIRMED and clears expiresAt for active PENDING reservations")
        void confirmActiveReservation_activePending_updatesStatusAndClearsExpiry() {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Instant futureExpiry = now.plus(10, ChronoUnit.MINUTES);
            Instant pastExpiry = now.minus(5, ChronoUnit.MINUTES);

            InventoryReservation activePending1 = reservationRepository.save(newReservation(cartId, itemId1, PENDING, futureExpiry));
            InventoryReservation activePending2 = reservationRepository.save(newReservation(cartId, itemId2, PENDING, futureExpiry));

            // Non-matching records
            InventoryReservation expiredPending = reservationRepository.save(newReservation(cartId, UUID.randomUUID(), PENDING, pastExpiry));
            InventoryReservation alreadyConfirmed = reservationRepository.save(newReservation(cartId, UUID.randomUUID(), CONFIRMED, futureExpiry));
            InventoryReservation otherCartPending = reservationRepository.save(newReservation("other-cart", itemId1, PENDING, futureExpiry));

            reservationRepository.flush();

            int updatedCount = reservationRepository.confirmActiveReservation(cartId, CONFIRMED, PENDING, now);

            assertThat(updatedCount).isEqualTo(2);

            entityManager.clear();

            InventoryReservation reloaded1 = reservationRepository.findById(activePending1.getId()).orElseThrow();
            InventoryReservation reloaded2 = reservationRepository.findById(activePending2.getId()).orElseThrow();
            InventoryReservation reloadedExpired = reservationRepository.findById(expiredPending.getId()).orElseThrow();
            InventoryReservation reloadedConfirmed = reservationRepository.findById(alreadyConfirmed.getId()).orElseThrow();
            InventoryReservation reloadedOtherCart = reservationRepository.findById(otherCartPending.getId()).orElseThrow();

            assertThat(reloaded1.getStatus()).isEqualTo(CONFIRMED);
            assertThat(reloaded1.getExpiresAt()).isNull();

            assertThat(reloaded2.getStatus()).isEqualTo(CONFIRMED);
            assertThat(reloaded2.getExpiresAt()).isNull();

            assertThat(reloadedExpired.getStatus()).isEqualTo(PENDING);
            assertThat(reloadedExpired.getExpiresAt()).isEqualTo(pastExpiry);

            assertThat(reloadedConfirmed.getStatus()).isEqualTo(CONFIRMED);
            assertThat(reloadedConfirmed.getExpiresAt()).isEqualTo(futureExpiry);

            assertThat(reloadedOtherCart.getStatus()).isEqualTo(PENDING);
            assertThat(reloadedOtherCart.getExpiresAt()).isEqualTo(futureExpiry);
        }

        @Test
        @DisplayName("Default method delegates correctly using current Instant")
        void confirmActiveReservation_defaultMethod_confirmsActiveReservations() {
            Instant futureExpiry = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

            InventoryReservation activePending = reservationRepository.save(newReservation(cartId, itemId1, PENDING, futureExpiry));
            reservationRepository.flush();

            int updatedCount = reservationRepository.confirmActiveReservation(cartId);

            assertThat(updatedCount).isEqualTo(1);

            entityManager.clear();

            InventoryReservation reloaded = reservationRepository.findById(activePending.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(CONFIRMED);
            assertThat(reloaded.getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("Returns 0 when reservation is expired or cartId does not match")
        void confirmActiveReservation_expiredOrNonExistent_returnsZero() {
            Instant pastExpiry = Instant.now().minus(5, ChronoUnit.MINUTES);

            reservationRepository.save(newReservation(cartId, itemId1, PENDING, pastExpiry));
            reservationRepository.flush();

            int updatedCount = reservationRepository.confirmActiveReservation(cartId);

            assertThat(updatedCount).isZero();
        }
    }
}