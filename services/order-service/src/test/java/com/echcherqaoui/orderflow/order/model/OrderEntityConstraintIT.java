package com.echcherqaoui.orderflow.order.model;

import com.echcherqaoui.orderflow.order.repository.OrderRepository;
import com.echcherqaoui.orderflow.order.support.WithPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderEntityConstraintIT implements WithPostgres {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    private Order newOrder() {
        return new Order()
              .setId(UUID.randomUUID())
              .setUserId("user-" + UUID.randomUUID())
              .setCartId("cart-" + UUID.randomUUID())
              .setCurrentSagaStep(INVENTORY_RESERVED)
              .setTotalAmountCents(1000L);
    }

    private static UUID insertBaseOrder(EntityManager em) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
            INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
            VALUES (:id, 'user1', :cartId, 'PENDING', 'INVENTORY_RESERVED', 'NONE', 1000, 0, now(), now())
        """).setParameter("id", id)
              .setParameter("cartId", "cart-" + id)
              .executeUpdate();
        return id;
    }

    private static Stream<Arguments> checkConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "invalid order status via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'user1', :cartId, 'INVALID_STATUS', 'INVENTORY_RESERVED', 'NONE', 1000, 0, now(), now())
                    """).setParameter("cartId", "cart-" + UUID.randomUUID())
                          .executeUpdate(),
                    "chk_orders_status"
              ),
              Arguments.of(
                    "negative total_amount_cents via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'user1', :cartId, 'PENDING', 'INVENTORY_RESERVED', 'NONE', -100, 0, now(), now())
                    """).setParameter("cartId", "cart-" + UUID.randomUUID())
                          .executeUpdate(),
                    "chk_orders_total_amount_cents_non_negative"
              ),
              Arguments.of(
                    "invalid refund_status via native UPDATE",
                    (Consumer<EntityManager>) em -> {
                        UUID orderId = insertBaseOrder(em);
                        em.createNativeQuery("UPDATE orders SET refund_status = 'INVALID_OUTCOME' WHERE id = :id")
                              .setParameter("id", orderId)
                              .executeUpdate();
                    },
                    "chk_orders_refund_status"
              ),
              Arguments.of(
                    "invalid inventory_release_status via native UPDATE",
                    (Consumer<EntityManager>) em -> {
                        UUID orderId = insertBaseOrder(em);
                        em.createNativeQuery("UPDATE orders SET inventory_release_status = 'UNKNOWN_STATE' WHERE id = :id")
                              .setParameter("id", orderId)
                              .executeUpdate();
                    },
                    "chk_orders_inventory_release_status"
              ),
              Arguments.of(
                    "NULL user_id via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
                        VALUES (gen_random_uuid(), NULL, :cartId, 'PENDING', 'INVENTORY_RESERVED', 'NONE', 1000, 0, now(), now())
                    """).setParameter("cartId", "cart-" + UUID.randomUUID())
                          .executeUpdate(),
                    "user_id"
              ),
              Arguments.of(
                    "NULL cart_id via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'user1', NULL, 'PENDING', 'INVENTORY_RESERVED', 'NONE', 1000, 0, now(), now())
                    """).executeUpdate(),
                    "cart_id"
              ),
              Arguments.of(
                    "NULL status via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'user1', :cartId, NULL, 'INVENTORY_RESERVED', 'NONE', 1000, 0, now(), now())
                    """).setParameter("cartId", "cart-" + UUID.randomUUID())
                          .executeUpdate(),
                    "status"
              ),
              Arguments.of(
                    "NULL current_saga_step via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'user1', :cartId, 'PENDING', NULL, 'NONE', 1000, 0, now(), now())
                    """).setParameter("cartId", "cart-" + UUID.randomUUID())
                          .executeUpdate(),
                    "current_saga_step"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("total_amount_cents = 0 (zero-value order) is valid at DB level")
        void totalAmountCents_zero_isValid() {
            Order order = newOrder().setTotalAmountCents(0L);
            assertThatCode(() -> orderRepository.saveAndFlush(order)).doesNotThrowAnyException();

            Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(reloaded.getTotalAmountCents()).isZero();
        }

        @Test
        @DisplayName("Optional compensation and payment fields remain NULL without violating table constraints")
        void nullableFields_stayNull_isValid() {
            Order order = newOrder()
                  .setPaymentIntentId(null)
                  .setRefundStatus(null)
                  .setInventoryReleaseStatus(null);

            assertThatCode(() -> orderRepository.saveAndFlush(order)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Cascade persistence creates order_items with proper foreign keys")
        void orderItemsCascade_persistsValidly() {
            Order order = newOrder();
            order.addItem(new OrderItem().setItemId(UUID.randomUUID()));

            assertThatCode(() -> orderRepository.saveAndFlush(order)).doesNotThrowAnyException();

            Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(reloaded.getItems()).hasSize(1);
        }
    }

    @ParameterizedTest(name = "[{index}] DB rejects {0}")
    @MethodSource("checkConstraintCases")
    void dbCheckConstraints_areEnforced(String description,
                                        Consumer<EntityManager> queryRunner,
                                        String expectedConstraintSnippet) {
        assertThatThrownBy(() -> queryRunner.accept(entityManager))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining(expectedConstraintSnippet)
              );
    }

    @Test
    @DisplayName("duplicate cart_id triggers uq_orders_cart_id unique constraint")
    void duplicateCartId_rejectedByUniqueConstraint() {
        String sharedCartId = "duplicate-cart-" + UUID.randomUUID();
        orderRepository.saveAndFlush(newOrder().setCartId(sharedCartId));

        Order duplicateOrder = newOrder().setCartId(sharedCartId);

        assertThatThrownBy(() -> orderRepository.saveAndFlush(duplicateOrder))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("uq_orders_cart_id")
              );
    }

    @Test
    @DisplayName("duplicate primary key triggers PK constraint")
    void duplicatePrimaryKey_rejectedByPkConstraint() {
        UUID existingId = orderRepository.saveAndFlush(newOrder()).getId();

        Query query = entityManager.createNativeQuery("""
            INSERT INTO orders (id, user_id, cart_id, status, current_saga_step, cancellation_reason, total_amount_cents, version, created_at, updated_at)
            VALUES (:id, 'user2', :cartId, 'PENDING', 'INVENTORY_RESERVED', 'NONE', 1000, 0, now(), now())
        """).setParameter("id", existingId)
              .setParameter("cartId", "cart-" + UUID.randomUUID());

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("orders_pkey")
              );
    }

    @Test
    @DisplayName("order_items foreign key fails when referenced order_id does not exist")
    void orderItem_invalidForeignKey_triggersFkConstraint() {
        UUID nonExistentOrderId = UUID.randomUUID();

        Query query = entityManager.createNativeQuery("""
            INSERT INTO order_items (id, order_id, item_id)
            VALUES (gen_random_uuid(), :orderId, gen_random_uuid())
        """).setParameter("orderId", nonExistentOrderId);

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("fk_order_items_orders")
              );
    }

    @Test
    @DisplayName("Deleting order cascades deletion to order_items")
    void deleteOrder_cascadesToOrderItems() {
        Order order = newOrder();
        order.addItem(new OrderItem().setItemId(UUID.randomUUID()));
        Order saved = orderRepository.saveAndFlush(order);

        orderRepository.delete(saved);
        orderRepository.flush();

        Number itemCount = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM order_items WHERE order_id = :orderId")
              .setParameter("orderId", saved.getId())
              .getSingleResult();

        assertThat(itemCount.longValue()).isZero();
    }
}