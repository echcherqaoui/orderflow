package com.echcherqaoui.orderflow.payment.model;

import com.echcherqaoui.orderflow.payment.repository.ProcessedWebhookEventRepository;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProcessedWebhookEventIT implements WithPostgres {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    private ProcessedWebhookEvent newProcessedWebhookEvent() {
        return new ProcessedWebhookEvent()
              .setEventId("evt_" + UUID.randomUUID())
              .setEventType("payment_intent.succeeded");
    }

    private static Stream<Arguments> checkConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "NULL event_id via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                              INSERT INTO processed_webhook_events (event_id, event_type, processed_at)
                              VALUES (NULL, 'payment_intent.succeeded', now())
                          """).executeUpdate(),
                    "event_id"
              ),
              Arguments.of(
                    "NULL event_type via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                                    INSERT INTO processed_webhook_events (event_id, event_type, processed_at)
                                    VALUES (:eventId, NULL, now())
                                """).setParameter("eventId", "evt_" + UUID.randomUUID())
                          .executeUpdate(),
                    "event_type"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("valid entity persists successfully with auto-generated processed_at timestamp")
        void validEntity_persistsSuccessfully() {
            ProcessedWebhookEvent event = newProcessedWebhookEvent();
            assertThatCode(() -> processedWebhookEventRepository.saveAndFlush(event)).doesNotThrowAnyException();

            ProcessedWebhookEvent reloaded = processedWebhookEventRepository.findById(event.getEventId()).orElseThrow();
            assertThat(reloaded.getEventId()).isEqualTo(event.getEventId());
            assertThat(reloaded.getEventType()).isEqualTo("payment_intent.succeeded");
            assertThat(reloaded.getProcessedAt()).isNotNull();
            assertThat(reloaded.isNew()).isTrue();
        }

        @Test
        @DisplayName("persisting entity explicitly marked as not new delegates handling correctly")
        void entityMarkedNotNew_canBeUpdated() {
            ProcessedWebhookEvent event = newProcessedWebhookEvent();
            processedWebhookEventRepository.saveAndFlush(event);

            ProcessedWebhookEvent reloaded = processedWebhookEventRepository.findById(event.getEventId()).orElseThrow();
            reloaded.markNotNew();

            assertThat(reloaded.isNew()).isFalse();
            assertThatCode(() -> processedWebhookEventRepository.saveAndFlush(reloaded)).doesNotThrowAnyException();
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
    @DisplayName("duplicate event_id triggers PK constraint")
    void duplicatePrimaryKey_rejectedByPkConstraint() {
        String existingId = processedWebhookEventRepository.saveAndFlush(newProcessedWebhookEvent()).getEventId();

        Query query = entityManager.createNativeQuery("""
                  INSERT INTO processed_webhook_events (event_id, event_type, processed_at)
                  VALUES (:eventId, 'payment_intent.payment_failed', now())
              """).setParameter("eventId", existingId);

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("processed_webhook_events_pkey")
              );
    }
}