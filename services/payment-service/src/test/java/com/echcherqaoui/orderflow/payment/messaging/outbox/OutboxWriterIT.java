package com.echcherqaoui.orderflow.payment.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentCancelledEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentChargedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentFailedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitializationFailedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitiatedEvent;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.support.WithKafka;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OutboxWriterIT implements WithPostgres, WithKafka {

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private EntityManager entityManager;

    private final UUID orderId = UUID.randomUUID();
    private final String triggerEventId = "evt-" + UUID.randomUUID();
    private final CreatePaymentIntentResponse pspResponse =
          new CreatePaymentIntentResponse("pi_stripe_12345", "secret_stripe_12345");

    @Nested
    @DisplayName("publishPaymentInitiatedEvent()")
    class PublishPaymentInitiatedEvent {

        @Test
        @Transactional
        @DisplayName("successfully serializes payload using Schema Registry wire format and persists complete outbox entity")
        void publishPaymentInitiatedEvent_success_persistsAndSerializesPayload() {
            outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, triggerEventId);

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> rows = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                        OutboxEvent.class
                  ).setParameter("orderId", orderId.toString())
                  .setParameter("aggregateType", "payment.events")
                  .getResultList();

            assertThat(rows).hasSize(1);
            OutboxEvent event = rows.getFirst();

            // Verify Outbox Metadata
            assertThat(event.getId()).isNotNull();
            assertThat(event.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(event.getAggregateType()).isEqualTo("payment.events");
            assertThat(event.getEventType()).isEqualTo("PaymentInitiatedEvent");
            assertThat(event.getCreatedAt()).isNotNull();
            assertThat(event.getPayload()).isNotEmpty();

            // Verify Protobuf Wire Format & Schema Registry Decoding
            Map<String, Object> deserializerConfig = new HashMap<>();
            deserializerConfig.put(
                  "schema.registry.url",
                  "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081)
            );
            deserializerConfig.put("specific.protobuf.value.type", PaymentInitiatedEvent.class);

            try (KafkaProtobufDeserializer<PaymentInitiatedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
                deserializer.configure(deserializerConfig, false);

                PaymentInitiatedEvent decoded = deserializer.deserialize("orderflow.payment.events", event.getPayload());

                // Payload assertions
                assertThat(decoded.getPaymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
                assertThat(decoded.getClientSecret()).isEqualTo(pspResponse.clientSecret());

                // Metadata assertions
                assertThat(decoded.getMetadata().getMessageId()).isNotBlank();
                assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
                assertThat(decoded.getMetadata().getCausationId()).isEqualTo(triggerEventId);
                assertThat(decoded.getMetadata().hasOccurredAt()).isTrue();
                assertThat(decoded.getMetadata().getSignature()).isNotBlank();
            }
        }

        @Test
        @DisplayName("throws IllegalTransactionStateException when called without an active transaction")
        void publishPaymentInitiatedEvent_noActiveTransaction_throwsIllegalTransactionStateException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, triggerEventId)
            ).isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when orderId is null")
        void publishPaymentInitiatedEvent_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitiatedEvent(null, pspResponse, triggerEventId)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when pspResponse is null")
        void publishPaymentInitiatedEvent_nullPspResponse_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitiatedEvent(orderId, null, triggerEventId)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("publishPaymentInitializationFailedEvent()")
    class PublishPaymentInitializationFailedEvent {

        private final String reason = "PSP connection failed: HTTP 503 Service Unavailable.";

        @Test
        @Transactional
        @DisplayName("successfully serializes payload using Schema Registry wire format and persists complete outbox entity")
        void publishPaymentInitializationFailedEvent_success_persistsAndSerializesPayload() {
            outboxWriter.publishPaymentInitializationFailedEvent(orderId, reason, triggerEventId);

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> rows = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                        OutboxEvent.class
                  ).setParameter("orderId", orderId.toString())
                  .setParameter("aggregateType", "payment.events")
                  .getResultList();

            assertThat(rows).hasSize(1);
            OutboxEvent event = rows.getFirst();

            // Verify Outbox Metadata
            assertThat(event.getId()).isNotNull();
            assertThat(event.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(event.getAggregateType()).isEqualTo("payment.events");
            assertThat(event.getEventType()).isEqualTo("PaymentInitializationFailedEvent");
            assertThat(event.getCreatedAt()).isNotNull();
            assertThat(event.getPayload()).isNotEmpty();

            // Verify Protobuf Wire Format & Schema Registry Decoding
            Map<String, Object> deserializerConfig = new HashMap<>();
            deserializerConfig.put(
                  "schema.registry.url",
                  "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081)
            );
            deserializerConfig.put("specific.protobuf.value.type", PaymentInitializationFailedEvent.class);

            try (KafkaProtobufDeserializer<PaymentInitializationFailedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
                deserializer.configure(deserializerConfig, false);

                PaymentInitializationFailedEvent decoded = deserializer.deserialize("orderflow.payment.events", event.getPayload());

                // Payload assertions
                assertThat(decoded.getReason()).isEqualTo(reason);

                // Metadata assertions
                assertThat(decoded.getMetadata().getMessageId()).isNotBlank();
                assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
                assertThat(decoded.getMetadata().getCausationId()).isEqualTo(triggerEventId);
                assertThat(decoded.getMetadata().hasOccurredAt()).isTrue();
                assertThat(decoded.getMetadata().getSignature()).isNotBlank();
            }
        }

        @Test
        @DisplayName("throws IllegalTransactionStateException when called without an active transaction")
        void publishPaymentInitializationFailedEvent_noActiveTransaction_throwsIllegalTransactionStateException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitializationFailedEvent(orderId, reason, triggerEventId)
            ).isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when orderId is null")
        void publishPaymentInitializationFailedEvent_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitializationFailedEvent(null, reason, triggerEventId)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when reason is null")
        void publishPaymentInitializationFailedEvent_nullReason_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitializationFailedEvent(orderId, null, triggerEventId)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("publishPaymentCancelledEvent()")
    class PublishPaymentCancelledEvent {

        private final String paymentIntentId = "pi_stripe_98765";
        private final String reason = "Customer requested cancellation";

        @Test
        @Transactional
        @DisplayName("successfully serializes payload using Schema Registry wire format and persists complete outbox entity")
        void publishPaymentCancelledEvent_success_persistsAndSerializesPayload() {
            outboxWriter.publishPaymentCancelledEvent(orderId, paymentIntentId, reason, triggerEventId);

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> rows = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                        OutboxEvent.class
                  ).setParameter("orderId", orderId.toString())
                  .setParameter("aggregateType", "payment.events")
                  .getResultList();

            assertThat(rows).hasSize(1);
            OutboxEvent event = rows.getFirst();

            // Verify Outbox Metadata
            assertThat(event.getId()).isNotNull();
            assertThat(event.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(event.getAggregateType()).isEqualTo("payment.events");
            assertThat(event.getEventType()).isEqualTo("PaymentCancelledEvent");
            assertThat(event.getCreatedAt()).isNotNull();
            assertThat(event.getPayload()).isNotEmpty();

            // Verify Protobuf Wire Format & Schema Registry Decoding
            Map<String, Object> deserializerConfig = new HashMap<>();
            deserializerConfig.put(
                  "schema.registry.url",
                  "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081)
            );
            deserializerConfig.put("specific.protobuf.value.type", PaymentCancelledEvent.class);

            try (KafkaProtobufDeserializer<PaymentCancelledEvent> deserializer = new KafkaProtobufDeserializer<>()) {
                deserializer.configure(deserializerConfig, false);

                PaymentCancelledEvent decoded = deserializer.deserialize("orderflow.payment.events", event.getPayload());

                // Payload assertions
                assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
                assertThat(decoded.getPaymentIntentId()).isEqualTo(paymentIntentId);
                assertThat(decoded.getReason()).isEqualTo(reason);

                // Metadata assertions
                assertThat(decoded.getMetadata().getMessageId()).isNotBlank();
                assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
                assertThat(decoded.getMetadata().getCausationId()).isEqualTo(triggerEventId);
                assertThat(decoded.getMetadata().hasOccurredAt()).isTrue();
                assertThat(decoded.getMetadata().getSignature()).isNotBlank();
            }
        }

        @Test
        @DisplayName("throws IllegalTransactionStateException when called without an active transaction")
        void publishPaymentCancelledEvent_noActiveTransaction_throwsIllegalTransactionStateException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentCancelledEvent(orderId, paymentIntentId, reason, triggerEventId)
            ).isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when orderId is null")
        void publishPaymentCancelledEvent_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentCancelledEvent(null, paymentIntentId, reason, triggerEventId)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when reason is null")
        void publishPaymentCancelledEvent_nullReason_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentCancelledEvent(orderId, paymentIntentId, null, triggerEventId)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when paymentIntentId is null due to Protobuf builder setter constraint")
        void publishPaymentCancelledEvent_nullPaymentIntentId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentCancelledEvent(orderId, null, reason, triggerEventId)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("writePaymentChargedEvent()")
    class WritePaymentChargedEvent {

        private final String paymentIntentId = "pi_stripe_charged_123";

        @Test
        @Transactional
        @DisplayName("successfully serializes payload using Schema Registry wire format and persists complete outbox entity")
        void writePaymentChargedEvent_success_persistsAndSerializesPayload() {
            outboxWriter.writePaymentChargedEvent(orderId.toString(), paymentIntentId);

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> rows = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                        OutboxEvent.class
                  ).setParameter("orderId", orderId.toString())
                  .setParameter("aggregateType", "payment.events")
                  .getResultList();

            assertThat(rows).hasSize(1);
            OutboxEvent event = rows.getFirst();

            // Verify Outbox Metadata
            assertThat(event.getId()).isNotNull();
            assertThat(event.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(event.getAggregateType()).isEqualTo("payment.events");
            assertThat(event.getEventType()).isEqualTo("PaymentChargedEvent");
            assertThat(event.getCreatedAt()).isNotNull();
            assertThat(event.getPayload()).isNotEmpty();

            // Verify Protobuf Wire Format & Schema Registry Decoding
            Map<String, Object> deserializerConfig = new HashMap<>();
            deserializerConfig.put(
                  "schema.registry.url",
                  "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081)
            );
            deserializerConfig.put("specific.protobuf.value.type", PaymentChargedEvent.class);

            try (KafkaProtobufDeserializer<PaymentChargedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
                deserializer.configure(deserializerConfig, false);

                PaymentChargedEvent decoded = deserializer.deserialize("orderflow.payment.events", event.getPayload());

                // Payload assertions
                assertThat(decoded.getPaymentIntentId()).isEqualTo(paymentIntentId);

                // Metadata assertions
                assertThat(decoded.getMetadata().getMessageId()).isNotBlank();
                assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
                assertThat(decoded.getMetadata().hasOccurredAt()).isTrue();
                assertThat(decoded.getMetadata().getSignature()).isNotBlank();
            }
        }

        @Test
        @DisplayName("throws IllegalTransactionStateException when called without an active transaction")
        void writePaymentChargedEvent_noActiveTransaction_throwsIllegalTransactionStateException() {
            String orderIdString = orderId.toString();
            assertThatThrownBy(() ->
                  outboxWriter.writePaymentChargedEvent(orderIdString, paymentIntentId)
            ).isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when orderId is null")
        void writePaymentChargedEvent_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.writePaymentChargedEvent(null, paymentIntentId)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when paymentIntentId is null")
        void writePaymentChargedEvent_nullPaymentIntentId_throwsNullPointerException() {
            String orderIdStr = orderId.toString();
            assertThatThrownBy(() ->
                  outboxWriter.writePaymentChargedEvent(orderIdStr, null)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("writePaymentFailedEvent()")
    class WritePaymentFailedEvent {

        private final String paymentIntentId = "pi_stripe_failed_123";
        private final String failureReason = "Insufficient funds";

        @Test
        @Transactional
        @DisplayName("successfully serializes payload using Schema Registry wire format and persists complete outbox entity")
        void writePaymentFailedEvent_success_persistsAndSerializesPayload() {
            outboxWriter.writePaymentFailedEvent(orderId.toString(), paymentIntentId, failureReason);

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> rows = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                        OutboxEvent.class
                  ).setParameter("orderId", orderId.toString())
                  .setParameter("aggregateType", "payment.events")
                  .getResultList();

            assertThat(rows).hasSize(1);
            OutboxEvent event = rows.getFirst();

            // Verify Outbox Metadata
            assertThat(event.getId()).isNotNull();
            assertThat(event.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(event.getAggregateType()).isEqualTo("payment.events");
            assertThat(event.getEventType()).isEqualTo("PaymentFailedEvent");
            assertThat(event.getCreatedAt()).isNotNull();
            assertThat(event.getPayload()).isNotEmpty();

            // Verify Protobuf Wire Format & Schema Registry Decoding
            Map<String, Object> deserializerConfig = new HashMap<>();
            deserializerConfig.put(
                  "schema.registry.url",
                  "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081)
            );
            deserializerConfig.put("specific.protobuf.value.type", PaymentFailedEvent.class);

            try (KafkaProtobufDeserializer<PaymentFailedEvent> deserializer = new KafkaProtobufDeserializer<>()) {
                deserializer.configure(deserializerConfig, false);

                PaymentFailedEvent decoded = deserializer.deserialize("orderflow.payment.events", event.getPayload());

                // Payload assertions
                assertThat(decoded.getPaymentIntentId()).isEqualTo(paymentIntentId);
                assertThat(decoded.getFailureReason()).isEqualTo(failureReason);

                // Metadata assertions
                assertThat(decoded.getMetadata().getMessageId()).isNotBlank();
                assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
                assertThat(decoded.getMetadata().hasOccurredAt()).isTrue();
                assertThat(decoded.getMetadata().getSignature()).isNotBlank();
            }
        }
        @Test
        @DisplayName("throws IllegalTransactionStateException when called without an active transaction")
        void writePaymentFailedEvent_noActiveTransaction_throwsIllegalTransactionStateException() {
            String orderIdStr = orderId.toString();
            assertThatThrownBy(() ->
                  outboxWriter.writePaymentFailedEvent(orderIdStr, paymentIntentId, failureReason)
            ).isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when orderId is null")
        void writePaymentFailedEvent_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.writePaymentFailedEvent(null, paymentIntentId, failureReason)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when paymentIntentId is null")
        void writePaymentFailedEvent_nullPaymentIntentId_throwsNullPointerException() {
            String orderIdStr = orderId.toString();
            assertThatThrownBy(() ->
                  outboxWriter.writePaymentFailedEvent(orderIdStr, null, failureReason)
            ).isInstanceOf(NullPointerException.class);
        }
    }
}