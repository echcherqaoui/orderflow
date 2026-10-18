package com.echcherqaoui.orderflow.inventory.model;

import com.echcherqaoui.orderflow.inventory.repository.ProcessedEventRepository;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProcessedEventConstraintIT implements WithPostgres {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;


    private static Stream<Arguments> constraintViolationCases() {
        UUID sharedId = UUID.randomUUID();
        return Stream.of(
              Arguments.of(
                    "duplicate event_id primary key insertion",
                    (Consumer<EntityManager>) em -> {
                        em.createNativeQuery("""
                            INSERT INTO processed_events (event_id, processed_at)
                            VALUES (:id, now())
                        """).setParameter("id", sharedId).executeUpdate();
                        em.flush();

                        em.createNativeQuery("""
                            INSERT INTO processed_events (event_id, processed_at)
                            VALUES (:id, now())
                        """).setParameter("id", sharedId).executeUpdate();
                        em.flush();
                    },
                    "processed_events_pkey"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("saving new ProcessedEvent persists successfully with generated timestamp")
        void saveEvent_validEvent_persistsSuccessfully() {
            UUID eventId = UUID.randomUUID();
            ProcessedEvent event = new ProcessedEvent(eventId);

            processedEventRepository.saveAndFlush(event);

            entityManager.clear();
            ProcessedEvent reloaded = processedEventRepository.findById(eventId).orElseThrow();

            assertThat(reloaded.getEventId()).isEqualTo(eventId);
            assertThat(reloaded.getProcessedAt()).isNotNull();
        }
    }

    @ParameterizedTest(name = "[{index}] DB rejects {0}")
    @MethodSource("constraintViolationCases")
    void dbConstraints_areEnforced(String description,
                                   Consumer<EntityManager> queryRunner,
                                   String expectedConstraintDetail) {
        assertThatThrownBy(() -> queryRunner.accept(entityManager))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining(expectedConstraintDetail)
              );
    }


    @Test
    @DisplayName("duplicate event_id via repository saveAndFlush triggers DataIntegrityViolationException")
    void duplicateEventId_rejectedByPrimaryKeyConstraint() {
        UUID eventId = UUID.randomUUID();
        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId));

        ProcessedEvent duplicate = new ProcessedEvent(eventId);

        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(duplicate))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }
}