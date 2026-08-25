package com.echcherqaoui.orderflow.order.model;

import com.echcherqaoui.orderflow.order.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
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

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderItemEntityConstraintIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    private Order createAndPersistOrder() {
        Order order = new Order()
              .setId(UUID.randomUUID())
              .setUserId("user-" + UUID.randomUUID())
              .setCartId("cart-" + UUID.randomUUID())
              .setCurrentSagaStep(INVENTORY_RESERVED)
              .setTotalAmountCents(1000L);

        return orderRepository.saveAndFlush(order);
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

    private static Stream<Arguments> notNullConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "NULL order_id via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO order_items (id, order_id, item_id)
                        VALUES (gen_random_uuid(), NULL, gen_random_uuid())
                    """).executeUpdate(),
                    "order_id"
              ),
              Arguments.of(
                    "NULL item_id via native INSERT",
                    (Consumer<EntityManager>) em -> {
                        UUID orderId = insertBaseOrder(em);
                        em.createNativeQuery("""
                            INSERT INTO order_items (id, order_id, item_id)
                            VALUES (gen_random_uuid(), :orderId, NULL)
                        """).setParameter("orderId", orderId)
                              .executeUpdate();
                    },
                    "item_id"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("Persists valid OrderItem via parent Order cascade")
        void validOrderItem_persistsSuccessfully() {
            Order order = createAndPersistOrder();
            order.addItem(new OrderItem().setItemId(UUID.randomUUID()));

            assertThatCode(() -> orderRepository.saveAndFlush(order)).doesNotThrowAnyException();

            Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(reloaded.getItems()).hasSize(1);
        }
    }

    @Test
    @DisplayName("Foreign key failure when referenced order_id does not exist")
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

    @ParameterizedTest(name = "[{index}] DB rejects {0}")
    @MethodSource("notNullConstraintCases")
    void notNullConstraints_areEnforced(String description,
                                        Consumer<EntityManager> queryRunner,
                                        String expectedColumnSnippet) {
        assertThatThrownBy(() -> queryRunner.accept(entityManager))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining(expectedColumnSnippet)
              );
    }

    @Test
    @DisplayName("Duplicate primary key triggers order_items_pkey constraint")
    void duplicatePrimaryKey_rejectedByPkConstraint() {
        Order order = createAndPersistOrder();
        UUID itemId = UUID.randomUUID();

        entityManager.createNativeQuery("""
            INSERT INTO order_items (id, order_id, item_id)
            VALUES (:id, :orderId, gen_random_uuid())
        """).setParameter("id", itemId)
              .setParameter("orderId", order.getId())
              .executeUpdate();

        Query duplicateInsertQuery = entityManager.createNativeQuery("""
            INSERT INTO order_items (id, order_id, item_id)
            VALUES (:id, :orderId, gen_random_uuid())
        """).setParameter("id", itemId)
              .setParameter("orderId", order.getId());

        assertThatThrownBy(duplicateInsertQuery::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("order_items_pkey")
              );
    }

}