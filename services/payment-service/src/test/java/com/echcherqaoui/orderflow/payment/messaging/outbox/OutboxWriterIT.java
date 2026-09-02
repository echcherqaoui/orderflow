package com.echcherqaoui.orderflow.payment.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitiatedEvent;
import com.echcherqaoui.orderflow.payment.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest
class OutboxWriterIT extends AbstractIntegrationTest {

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
            outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse);

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> rows = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                        OutboxEvent.class
                  )
                  .setParameter("orderId", orderId.toString())
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
            deserializerConfig.put("schema.registry.url",
                  "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
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
                  outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse)
            ).isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when orderId is null")
        void publishPaymentInitiatedEvent_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitiatedEvent(null, triggerEventId, pspResponse)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @Transactional
        @DisplayName("throws NullPointerException when pspResponse is null")
        void publishPaymentInitiatedEvent_nullPspResponse_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, null)
            ).isInstanceOf(NullPointerException.class);
        }
    }
}