package com.echcherqaoui.orderflow.order.model;

import com.echcherqaoui.orderflow.order.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import com.echcherqaoui.orderflow.order.repository.OrderSagaHistoryRepository;
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

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderSagaHistoryEntityConstraintIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OrderSagaHistoryRepository sagaHistoryRepository;

    private OrderSagaHistory newSagaHistory() {
        return new OrderSagaHistory()
              .setOrderId(UUID.randomUUID())
              .setStep(INVENTORY_RESERVED)
              .setStatus(SUCCEEDED)
              .setTriggerEvent("INVENTORY_RESERVED_EVENT")
              .setTriggerEventId("evt-" + UUID.randomUUID())
              .setMetadata(Map.of("inventoryId", "inv-123", "quantity", 2));
    }



    private static Stream<Arguments> notNullConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "NULL order_id via native INSERT",
                    (Function<EntityManager, Query>) em -> em.createNativeQuery("""
                        INSERT INTO order_saga_history (order_id, step, status, created_at)
                        VALUES (NULL, 'INVENTORY_RESERVED', 'SUCCESS', now())
                    """),
                    "order_id"
              ),
              Arguments.of(
                    "NULL step via native INSERT",
                    (Function<EntityManager, Query>) em -> em.createNativeQuery("""
                        INSERT INTO order_saga_history (order_id, step, status, created_at)
                        VALUES (gen_random_uuid(), NULL, 'SUCCESS', now())
                    """),
                    "step"
              ),
              Arguments.of(
                    "NULL status via native INSERT",
                    (Function<EntityManager, Query>) em -> em.createNativeQuery("""
                        INSERT INTO order_saga_history (order_id, step, status, created_at)
                        VALUES (gen_random_uuid(), 'INVENTORY_RESERVED', NULL, now())
                    """),
                    "status"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("Persists full OrderSagaHistory record with JSONB metadata and populates auto-generated ID and createdAt")
        void fullSagaHistory_persistsSuccessfully() {
            OrderSagaHistory history = newSagaHistory();

            assertThatCode(() -> sagaHistoryRepository.saveAndFlush(history)).doesNotThrowAnyException();

            OrderSagaHistory reloaded = sagaHistoryRepository.findById(history.getId()).orElseThrow();
            assertThat(reloaded.getId()).isNotNull();
            assertThat(reloaded.getCreatedAt()).isNotNull();
            assertThat(reloaded.getOrderId()).isEqualTo(history.getOrderId());
            assertThat(reloaded.getStep()).isEqualTo(INVENTORY_RESERVED);
            assertThat(reloaded.getStatus()).isEqualTo(SUCCEEDED);
            assertThat(reloaded.getTriggerEvent()).isEqualTo(history.getTriggerEvent());
            assertThat(reloaded.getTriggerEventId()).isEqualTo(history.getTriggerEventId());
            assertThat(reloaded.getMetadata())
                  .containsEntry("inventoryId", "inv-123")
                  .containsEntry("quantity", 2);
        }

        @Test
        @DisplayName("Optional trigger fields and metadata remain NULL without violating table constraints")
        void nullableFields_stayNull_isValid() {
            OrderSagaHistory history = new OrderSagaHistory()
                  .setOrderId(UUID.randomUUID())
                  .setStep(SagaStep.ORDER_CREATED)
                  .setStatus(SagaStepStatus.STARTED)
                  .setTriggerEvent(null)
                  .setTriggerEventId(null)
                  .setMetadata(null);

            assertThatCode(() -> sagaHistoryRepository.saveAndFlush(history)).doesNotThrowAnyException();

            OrderSagaHistory reloaded = sagaHistoryRepository.findById(history.getId()).orElseThrow();
            assertThat(reloaded.getTriggerEvent()).isNull();
            assertThat(reloaded.getTriggerEventId()).isNull();
            assertThat(reloaded.getMetadata()).isNull();
        }
    }

    @ParameterizedTest(name = "[{index}] DB rejects {0}")
    @MethodSource("notNullConstraintCases")
    void notNullConstraints_areEnforced(String description,
                                        Function<EntityManager, Query> queryBuilder,
                                        String expectedColumnSnippet) {
        Query query = queryBuilder.apply(entityManager);

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining(expectedColumnSnippet)
              );
    }

    @Test
    @DisplayName("Invalid JSON format in jsonb column is rejected by DB")
    void invalidJsonMetadata_rejectedByDb() {
        Query query = entityManager.createNativeQuery("""
            INSERT INTO order_saga_history (order_id, step, status, metadata, created_at)
            VALUES (gen_random_uuid(), 'INVENTORY_RESERVED', 'SUCCESS', 'invalid-json'::jsonb, now())
        """);

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }
}