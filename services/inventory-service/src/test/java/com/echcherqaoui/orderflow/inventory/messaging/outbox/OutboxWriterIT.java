package com.echcherqaoui.orderflow.inventory.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryConfirmationFailedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryConfirmedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryReleasedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtendedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtensionFailedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.StockProvisionRejectedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.StockProvisionedEvent;
import com.echcherqaoui.orderflow.inventory.support.WithKafka;
import com.echcherqaoui.orderflow.inventory.support.WithPostgres;
import com.echcherqaoui.orderflow.util.InstantConverter;
import com.google.protobuf.Message;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OutboxWriterIT implements WithPostgres, WithKafka {

    private static final String AGGREGATE_TYPE = "inventory.events";
    private static final String ITEM_AGGREGATE_TYPE = "inventory.item-events";
    private static final String TOPIC_CONTEXT = "inventory-serialization-context";

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private <T extends Message> Map<String, Object> createDeserializerConfig(Class<T> targetClass) {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url",
              "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        config.put("specific.protobuf.value.type", targetClass);
        return config;
    }

    private OutboxEvent fetchOutboxEvent(String aggregateId, String aggregateType) {
        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> rows = entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :aggregateId AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              )
              .setParameter("aggregateId", aggregateId)
              .setParameter("aggregateType", aggregateType)
              .getResultList();

        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private OutboxEvent fetchOutboxEvent(UUID orderId) {
        return fetchOutboxEvent(orderId.toString(), AGGREGATE_TYPE);
    }

    @Test
    @Transactional
    @DisplayName("publishReservationExtendedEvent writes decodable Confluent wire payload to outbox")
    void publishReservationExtendedEvent_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String causationId = "msg-causation-100";
        String cartId = "cart-123";
        Instant newExpiresAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        outboxWriter.publishReservationExtendedEvent(orderId, causationId, cartId, newExpiresAt);

        OutboxEvent event = fetchOutboxEvent(orderId);
        assertThat(event.getEventType()).isEqualTo("ReservationExtendedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<ReservationExtendedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(ReservationExtendedEvent.class), false);

            ReservationExtendedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getCartId()).isEqualTo(cartId);
            assertThat(decoded.getNewExpiresAt().getSeconds()).isEqualTo(InstantConverter.toTimestamp(newExpiresAt).getSeconds());
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("publishReservationExtensionFailedEvent writes decodable Confluent wire payload to outbox")
    void publishReservationExtensionFailedEvent_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String causationId = "msg-causation-200";
        String cartId = "cart-456";
        String reason = "RESERVATION_EXPIRED";

        outboxWriter.publishReservationExtensionFailedEvent(orderId, causationId, cartId, reason);

        OutboxEvent event = fetchOutboxEvent(orderId);
        assertThat(event.getEventType()).isEqualTo("ReservationExtensionFailedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<ReservationExtensionFailedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(ReservationExtensionFailedEvent.class), false);

            ReservationExtensionFailedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getCartId()).isEqualTo(cartId);
            assertThat(decoded.getReason()).isEqualTo(reason);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("publishInventoryReleasedEvent writes decodable Confluent wire payload to outbox")
    void publishInventoryReleasedEvent_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String causationId = "msg-causation-300";
        String cartId = "cart-789";

        outboxWriter.publishInventoryReleasedEvent(orderId, causationId, cartId);

        OutboxEvent event = fetchOutboxEvent(orderId);
        assertThat(event.getEventType()).isEqualTo("InventoryReleasedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<InventoryReleasedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(InventoryReleasedEvent.class), false);

            InventoryReleasedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getCartId()).isEqualTo(cartId);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("publishInventoryReleasedEvent handles null causationId without populating causationId field")
    void publishInventoryReleasedEvent_nullCausationId_omitsCausationIdField() {
        UUID orderId = UUID.randomUUID();
        String cartId = "cart-999";

        outboxWriter.publishInventoryReleasedEvent(orderId, null, cartId);

        OutboxEvent event = fetchOutboxEvent(orderId);

        try (KafkaProtobufDeserializer<InventoryReleasedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(InventoryReleasedEvent.class), false);

            InventoryReleasedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getMetadata().getCausationId()).isEmpty();
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
        }
    }

    @Test
    @Transactional
    @DisplayName("publishInventoryConfirmedEvent writes decodable Confluent wire payload to outbox")
    void publishInventoryConfirmedEvent_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String cartId = "cart-111";
        String causationId = "msg-causation-400";

        outboxWriter.publishInventoryConfirmedEvent(orderId, cartId, causationId);

        OutboxEvent event = fetchOutboxEvent(orderId);
        assertThat(event.getEventType()).isEqualTo("InventoryConfirmedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<InventoryConfirmedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(InventoryConfirmedEvent.class), false);

            InventoryConfirmedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getCartId()).isEqualTo(cartId);
            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("publishInventoryConfirmationFailedEvent writes decodable Confluent wire payload to outbox")
    void publishInventoryConfirmationFailedEvent_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String causationId = "msg-causation-500";
        String cartId = "cart-222";
        String reason = "STOCK_UNAVAILABLE";

        outboxWriter.publishInventoryConfirmationFailedEvent(orderId, causationId, cartId, reason);

        OutboxEvent event = fetchOutboxEvent(orderId);
        assertThat(event.getEventType()).isEqualTo("InventoryConfirmationFailedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<InventoryConfirmationFailedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(InventoryConfirmationFailedEvent.class), false);

            InventoryConfirmationFailedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getCartId()).isEqualTo(cartId);
            assertThat(decoded.getReason()).isEqualTo(reason);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("publishStockProvisionedEvent writes decodable Confluent wire payload to outbox")
    void publishStockProvisionedEvent_persistsRealSerializedPayload() {
        String itemName = "item-test-abc";
        String causationId = "msg-causation-600";
        int quantity = 50;

        outboxWriter.publishStockProvisionedEvent(causationId, itemName, quantity);

        OutboxEvent event = fetchOutboxEvent(itemName, ITEM_AGGREGATE_TYPE);
        assertThat(event.getEventType()).isEqualTo("StockProvisionedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<StockProvisionedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(StockProvisionedEvent.class), false);

            StockProvisionedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getItemName()).isEqualTo(itemName);
            assertThat(decoded.getQuantity()).isEqualTo(quantity);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(itemName);
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @Transactional
    @DisplayName("publishStockProvisionRejectedEvent writes decodable Confluent wire payload to outbox")
    void publishStockProvisionRejectedEvent_persistsRealSerializedPayload() {
        String itemName = "item-test-xyz";
        String causationId = "msg-causation-700";
        String reason = "PRICE_MISMATCH";

        outboxWriter.publishStockProvisionRejectedEvent(causationId, itemName, reason);

        OutboxEvent event = fetchOutboxEvent(itemName, ITEM_AGGREGATE_TYPE);
        assertThat(event.getEventType()).isEqualTo("StockProvisionRejectedEvent");
        assertThat(event.getPayload()).isNotEmpty();

        try (KafkaProtobufDeserializer<StockProvisionRejectedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(createDeserializerConfig(StockProvisionRejectedEvent.class), false);

            StockProvisionRejectedEvent decoded = deserializer.deserialize(TOPIC_CONTEXT, event.getPayload());

            assertThat(decoded.getItemName()).isEqualTo(itemName);
            assertThat(decoded.getReason()).isEqualTo(reason);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(itemName);
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @DisplayName("publish methods throw NullPointerException on null required parameters")
    void publish_nullArguments_throwsNullPointerException() {
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishReservationExtendedEvent(null, "c1", "cart-1", now)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishReservationExtendedEvent(orderId, "c1", null, now)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishReservationExtendedEvent(orderId, "c1", "cart-1", null)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishReservationExtensionFailedEvent(null, "c1", "cart-1", "REASON")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishReservationExtensionFailedEvent(orderId, "c1", null, "REASON")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishReservationExtensionFailedEvent(orderId, "c1", "cart-1", null)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishInventoryReleasedEvent(null, "c1", "cart-1")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishInventoryReleasedEvent(orderId, "c1", null)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishInventoryConfirmedEvent(null, "cart-1", "c1")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishInventoryConfirmedEvent(orderId, null, "c1")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishInventoryConfirmationFailedEvent(null, "c1", "cart-1", "REASON")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishInventoryConfirmationFailedEvent(orderId, "c1", null, "REASON")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishInventoryConfirmationFailedEvent(orderId, "c1", "cart-1", null)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishStockProvisionedEvent(null, "item-1", 10)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishStockProvisionedEvent("c1", null, 10)))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishStockProvisionRejectedEvent(null, "item-1", "REASON")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishStockProvisionRejectedEvent("c1", null, "REASON")))
              .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
              outboxWriter.publishStockProvisionRejectedEvent("c1", "item-1", null)))
              .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("publish methods without active transaction throw IllegalTransactionStateException")
    void publish_noActiveTransaction_throwsIllegalTransactionStateException() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> outboxWriter.publishInventoryReleasedEvent(orderId, "msg-1", "cart-1"))
              .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> outboxWriter.publishReservationExtendedEvent(orderId, "msg-1", "cart-1", Instant.now()))
              .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> outboxWriter.publishReservationExtensionFailedEvent(orderId, "msg-1", "cart-1", "REASON"))
              .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> outboxWriter.publishInventoryConfirmedEvent(orderId, "cart-1", "msg-1"))
              .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> outboxWriter.publishInventoryConfirmationFailedEvent(orderId, "msg-1", "cart-1", "REASON"))
              .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> outboxWriter.publishStockProvisionedEvent("msg-1", "item-1", 10))
              .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> outboxWriter.publishStockProvisionRejectedEvent("msg-1", "item-1", "REASON"))
              .isInstanceOf(IllegalTransactionStateException.class);
    }
}