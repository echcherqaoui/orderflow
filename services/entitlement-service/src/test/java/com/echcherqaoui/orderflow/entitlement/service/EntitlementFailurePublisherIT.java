package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementFailedEvent;
import com.echcherqaoui.orderflow.entitlement.support.WithKafka;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EntitlementFailurePublisherIT implements WithPostgres, WithKafka {

    private static final String TOPIC_CONTEXT = "entitlement-failure-serialization-context";
    private static final String SAGA_EVENTS_AGGREGATE_TYPE = "entitlement.events";

    @Autowired
    private EntitlementFailurePublisher failurePublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAllInBatch();
    }

    private Map<String, Object> createDeserializerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url",
              "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        config.put("specific.protobuf.value.type", EntitlementFailedEvent.class);
        return config;
    }

    private OutboxEvent fetchSagaOutboxEvent(String orderId) {
        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> rows = entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              )
              .setParameter("orderId", orderId)
              .setParameter("aggregateType", SAGA_EVENTS_AGGREGATE_TYPE)
              .getResultList();

        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @Test
    @Transactional
    @DisplayName("publishFailure commits EntitlementFailedEvent in REQUIRES_NEW transaction and writes decodable payload")
    void publishFailure_success_persistsAndCommitsDecodableEvent() {
        UUID orderId = UUID.randomUUID();
        String userId = "user-999";
        String causationId = UUID.randomUUID().toString();
        String reason = "KEY_POOL_EXHAUSTED";

        failurePublisher.publishFailure(orderId, userId, causationId, reason);

        OutboxEvent event = fetchSagaOutboxEvent(orderId.toString());
        assertThat(event.getEventType()).isEqualTo("EntitlementFailedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<EntitlementFailedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(), false);

            EntitlementFailedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getUserId()).isEqualTo(userId);
            assertThat(decoded.getReason()).isEqualTo(reason);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }
}