package com.echcherqaoui.orderflow.entitlement.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementFailedEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementGrantedEvent;
import com.echcherqaoui.orderflow.entitlement.support.WithKafka;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import com.echcherqaoui.orderflow.entitlement.util.ItemIds;
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

    private static final String TOPIC_CONTEXT = "entitlement-serialization-context";
    private static final String ITEM_EVENTS_AGGREGATE_TYPE = "entitlement.item-events";
    private static final String SAGA_EVENTS_AGGREGATE_TYPE = "entitlement.events";

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

    private Map<String, Object> createEntitlementDeserializerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url",
              "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        config.put("specific.protobuf.value.type", EntitlementGrantedEvent.class);
        return config;
    }

    private Map<String, Object> createEntitlementFailedDeserializerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url",
              "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        config.put("specific.protobuf.value.type", EntitlementFailedEvent.class);
        return config;
    }

    private OutboxEvent fetchOutboxEvent(String itemName) {
        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> rows = entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :itemName AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              )
              .setParameter("itemName", itemName)
              .setParameter("aggregateType", ITEM_EVENTS_AGGREGATE_TYPE)
              .getResultList();

        assertThat(rows).hasSize(1);
        return rows.getFirst();
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
    @DisplayName("publishDigitalStockProvisionedEvent writes decodable Confluent wire payload to outbox")
    void publishDigitalStockProvisionedEvent_persistsRealSerializedPayload() {
        String itemName = "prod-" + UUID.randomUUID();
        UUID itemId = ItemIds.fromName(itemName);

        Long priceCents = 1999L;
        Integer count = 10;

        outboxWriter.publishDigitalStockProvisionedEvent(itemId, itemName, priceCents, count);

        OutboxEvent event = fetchOutboxEvent(itemName);
        assertThat(event.getEventType()).isEqualTo("DigitalStockProvisionedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<DigitalStockProvisionedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(), false);

            DigitalStockProvisionedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getItemName()).isEqualTo(itemName);
            assertThat(decoded.getPriceCents()).isEqualTo(priceCents);
            assertThat(decoded.getCountAdded()).isEqualTo(count);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(itemId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEmpty();
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("writeEntitlementGranted writes decodable Confluent wire payload to outbox")
    void writeEntitlementGranted_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String userId = "user-789";
        List<String> accessKeys = List.of("KEY-ABC-123", "KEY-XYZ-789");
        String triggerEventId = UUID.randomUUID().toString();

        outboxWriter.writeEntitlementGranted(orderId, userId, accessKeys, triggerEventId);

        OutboxEvent event = fetchSagaOutboxEvent(orderId.toString());
        assertThat(event.getEventType()).isEqualTo("EntitlementGrantedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<EntitlementGrantedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createEntitlementDeserializerConfig(), false);

            EntitlementGrantedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getUserId()).isEqualTo(userId);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(triggerEventId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("writeEntitlementFailed writes decodable Confluent wire payload to outbox")
    void writeEntitlementFailed_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String userId = "user-789";
        String reason = "KEY_POOL_EXHAUSTED";
        String triggerEventId = UUID.randomUUID().toString();

        outboxWriter.writeEntitlementFailed(orderId, userId, reason, triggerEventId);

        OutboxEvent event = fetchSagaOutboxEvent(orderId.toString());
        assertThat(event.getEventType()).isEqualTo("EntitlementFailedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<EntitlementFailedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createEntitlementFailedDeserializerConfig(), false);

            EntitlementFailedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getUserId()).isEqualTo(userId);
            assertThat(decoded.getReason()).isEqualTo(reason);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(triggerEventId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @DisplayName("publish methods throw NullPointerException on null required parameters")
    void publish_nullArguments_throwsNullPointerException() {
        String itemName = "prod-" + UUID.randomUUID();
        UUID itemId = ItemIds.fromName(itemName);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishDigitalStockProvisionedEvent(itemId, null, 1999L, 5)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishDigitalStockProvisionedEvent(itemId, itemName, null, 5)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishDigitalStockProvisionedEvent(itemId, itemName, 1999L, null)))
              .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("publish method without active transaction throws IllegalTransactionStateException")
    void publish_noActiveTransaction_throwsIllegalTransactionStateException() {
        String itemName = "prod-" + UUID.randomUUID();
        UUID itemId = ItemIds.fromName(itemName);

        assertThatThrownBy(() -> outboxWriter.publishDigitalStockProvisionedEvent(itemId, itemName, 1999L, 5))
              .isInstanceOf(IllegalTransactionStateException.class);
    }
}