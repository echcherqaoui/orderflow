package com.echcherqaoui.orderflow.entitlement.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.entitlement.support.WithKafka;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OutboxWriterIT implements WithPostgres, WithKafka {

    private static final String AGGREGATE_TYPE = "entitlement.events";
    private static final String TOPIC_CONTEXT = "entitlement-serialization-context";

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Map<String, Object> createDeserializerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url",
              "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        config.put("specific.protobuf.value.type", DigitalStockProvisionedEvent.class);
        return config;
    }

    private OutboxEvent fetchOutboxEvent(String productId) {
        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> rows = entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :productId AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              )
              .setParameter("productId", productId)
              .setParameter("aggregateType", AGGREGATE_TYPE)
              .getResultList();

        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @Test
    @Transactional
    @DisplayName("publishDigitalStockProvisionedEvent writes decodable Confluent wire payload to outbox")
    void publishDigitalStockProvisionedEvent_persistsRealSerializedPayload() {
        String productId = "prod-" + UUID.randomUUID();
        Long priceCents = 1999L;
        Integer count = 10;

        outboxWriter.publishDigitalStockProvisionedEvent(productId, priceCents, count);

        OutboxEvent event = fetchOutboxEvent(productId);
        assertThat(event.getEventType()).isEqualTo("DigitalStockProvisionedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<DigitalStockProvisionedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(), false);

            DigitalStockProvisionedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getProductId()).isEqualTo(productId);
            assertThat(decoded.getPriceCents()).isEqualTo(priceCents);
            assertThat(decoded.getCountAdded()).isEqualTo(count);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(productId);
            assertThat(decoded.getMetadata().getCausationId()).isEmpty();
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @DisplayName("publish methods throw NullPointerException on null required parameters")
    void publish_nullArguments_throwsNullPointerException() {
        String productId = "prod-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishDigitalStockProvisionedEvent(null, 1999L, 5)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishDigitalStockProvisionedEvent(productId, null, 5)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishDigitalStockProvisionedEvent(productId, 1999L, null)))
              .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("publish method without active transaction throws IllegalTransactionStateException")
    void publish_noActiveTransaction_throwsIllegalTransactionStateException() {
        String productId = "prod-" + UUID.randomUUID();

        assertThatThrownBy(() -> outboxWriter.publishDigitalStockProvisionedEvent(productId, 1999L, 5))
              .isInstanceOf(IllegalTransactionStateException.class);
    }
}