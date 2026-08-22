package com.echcherqaoui.orderflow.inventory.model;

import com.echcherqaoui.orderflow.inventory.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ItemEntityConstraintIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    private Item newItem(int total, int remaining) {
        return new Item()
              .setName("test-item-" + UUID.randomUUID())
              .setPriceCents(1000)
              .setTotalEarlyAccessUnits(total)
              .setRemainingUnits(remaining);
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("remaining_units = 0 (lower bound) is valid via persist and native UPDATE")
        void remainingUnits_lowerBoundary_isValid() {
            Item item = itemRepository.saveAndFlush(newItem(10, 0));

            assertThatCode(() -> {
                entityManager.createNativeQuery("UPDATE items SET remaining_units = 0 WHERE id = :id")
                      .setParameter("id", item.getId())
                      .executeUpdate();
                entityManager.flush();
            }).doesNotThrowAnyException();

            Item reloaded = itemRepository.findById(item.getId()).orElseThrow();
            assertThat(reloaded.getRemainingUnits()).isZero();
        }

        @Test
        @DisplayName("price_cents = 0 (lower bound) is valid at DB level")
        void priceCents_lowerBoundary_isValid() {
            Item item = newItem(10, 10).setPriceCents(0);
            assertThatCode(() -> itemRepository.saveAndFlush(item)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("remainingUnits = 0 survives a bulk decrement to exactly zero")
        void zeroRemainingUnits_isValid_viaBulkDecrement() {
            Item item = itemRepository.saveAndFlush(newItem(1, 1));

            int updated = itemRepository.decrementStockBatch(Set.of(item.getId()));
            entityManager.flush();
            entityManager.clear();

            assertThat(updated).isEqualTo(1);
            Item reloaded = itemRepository.findById(item.getId()).orElseThrow();
            assertThat(reloaded.getRemainingUnits()).isZero();
        }
    }

    @ParameterizedTest(name = "[{index}] DB rejects {0}")
    @MethodSource("checkConstraintCases")
    void dbCheckConstraints_areEnforced(String description,
                                        Consumer<EntityManager> queryRunner,
                                        String expectedConstraintName) {
        assertThatThrownBy(() -> queryRunner.accept(entityManager))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining(expectedConstraintName)
              );
    }

    private static Stream<Arguments> checkConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "remaining_units < 0 via native UPDATE",
                    (Consumer<EntityManager>) em -> {
                        UUID id = insertItem(em, 5, 0);
                        em.createNativeQuery("UPDATE items SET remaining_units = -1 WHERE id = :id")
                              .setParameter("id", id)
                              .executeUpdate();
                    },
                    "chk_items_remaining_units_non_negative"
              ),
              Arguments.of(
                    "remaining_units > total_early_access_units via native UPDATE",
                    (Consumer<EntityManager>) em -> {
                        UUID id = insertItem(em, 5, 5);
                        em.createNativeQuery("UPDATE items SET remaining_units = 6 WHERE id = :id")
                              .setParameter("id", id)
                              .executeUpdate();
                    },
                    "chk_items_remaining_within_total"
              ),
              Arguments.of(
                    "price_cents < 0 via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                                    INSERT INTO items (id, name, price_cents, total_early_access_units, remaining_units, created_at, updated_at)
                                    VALUES (gen_random_uuid(), :name, -1, 5, 5, now(), now())
                                """)
                          .setParameter("name", "bad-price-" + UUID.randomUUID())
                          .executeUpdate(),
                    "chk_items_price_cents_non_negative"
              )
        );
    }

    private static UUID insertItem(EntityManager em, int total, int remaining) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
                        INSERT INTO items (id, name, price_cents, total_early_access_units, remaining_units, created_at, updated_at)
                        VALUES (:id, :name, 1000, :total, :remaining, now(), now())
                    """)
              .setParameter("id", id)
              .setParameter("name", "item-" + id)
              .setParameter("total", total)
              .setParameter("remaining", remaining)
              .executeUpdate();

        return id;
    }

    @Test
    @DisplayName("duplicate item name triggers uq_items_name unique constraint")
    void duplicateName_rejectedByUniqueConstraint() {
        String sharedName = "duplicate-name-" + UUID.randomUUID();
        itemRepository.saveAndFlush(newItem(5, 5).setName(sharedName));

        assertThatThrownBy(() -> itemRepository.saveAndFlush(newItem(5, 5).setName(sharedName)))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("uq_items_name")
              );
    }

    @Test
    @DisplayName("Demonstrate schema gap: total_early_access_units <= 0 is rejected by Bean Validation but bypasses DB check")
    void totalUnitsZero_bypassesDb_becauseNoCheckConstraintExists() {
        // Native SQL bypasses @Min(1) Bean Validation because there is no DB CHECK constraint for total_early_access_units
        assertThatCode(() -> entityManager.createNativeQuery("""
                    INSERT INTO items (id, name, price_cents, total_early_access_units, remaining_units, created_at, updated_at)
                    VALUES (gen_random_uuid(), :name, 1000, 0, 0, now(), now())
                    """)
              .setParameter("name", "zero-total-" + UUID.randomUUID())
              .executeUpdate()).doesNotThrowAnyException();
    }
}