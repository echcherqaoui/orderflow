package com.echcherqaoui.orderflow.order.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ExtendReservationCommand;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ReleaseInventoryCommand;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.CancelPaymentCommand;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.ChargePaymentCommand;
import com.echcherqaoui.orderflow.order.support.WithKafka;
import com.echcherqaoui.orderflow.order.support.WithPostgres;
import com.google.protobuf.Message;
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
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OutboxWriterIT implements WithPostgres, WithKafka {

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private EntityManager entityManager;

    @Nested
    @DisplayName("Publishing Commands")
    class PublishingCommands {

        @Test
        @Transactional
        @DisplayName("publishChargePaymentCommand persists wire-format outbox row decodable to ChargePaymentCommand")
        void publishChargePaymentCommand_persistsRealSerializedPayload() {
            UUID orderId = UUID.randomUUID();
            String userId = "user@example.com";
            long totalPriceCents = 3299L;

            outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents);

            OutboxEvent event = findAndAssertSingleOutboxEvent(orderId, "payment.commands", "ChargePaymentCommand");
            ChargePaymentCommand decoded = deserialize(event.getPayload(), ChargePaymentCommand.class);

            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getUserId()).isEqualTo(userId);
            assertThat(decoded.getTotalPriceCents()).isEqualTo(totalPriceCents);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }

        @Test
        @Transactional
        @DisplayName("publishReleaseInventoryCommand persists wire-format outbox row decodable to ReleaseInventoryCommand")
        void publishReleaseInventoryCommand_persistsRealSerializedPayload() {
            UUID orderId = UUID.randomUUID();
            String causationMessageId = UUID.randomUUID().toString();
            String cartId = "cart-123";

            outboxWriter.publishReleaseInventoryCommand(orderId, causationMessageId, cartId);

            OutboxEvent event = findAndAssertSingleOutboxEvent(orderId, "inventory.commands", "ReleaseInventoryCommand");
            ReleaseInventoryCommand decoded = deserialize(event.getPayload(), ReleaseInventoryCommand.class);

            assertThat(decoded.getCartId()).isEqualTo(cartId);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationMessageId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }

        @Test
        @Transactional
        @DisplayName("publishExtendReservationCommand persists wire-format outbox row decodable to ExtendReservationCommand")
        void publishExtendReservationCommand_persistsRealSerializedPayload() {
            UUID orderId = UUID.randomUUID();
            String causationMessageId = UUID.randomUUID().toString();
            String cartId = "cart-456";

            outboxWriter.publishExtendReservationCommand(orderId, causationMessageId, cartId);

            OutboxEvent event = findAndAssertSingleOutboxEvent(orderId, "inventory.commands", "ExtendReservationCommand");
            ExtendReservationCommand decoded = deserialize(event.getPayload(), ExtendReservationCommand.class);

            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getCartId()).isEqualTo(cartId);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationMessageId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }

        @Test
        @Transactional
        @DisplayName("publishCancelPaymentCommand persists wire-format outbox row decodable to CancelPaymentCommand")
        void publishCancelPaymentCommand_persistsRealSerializedPayload() {
            UUID orderId = UUID.randomUUID();
            String causationMessageId = UUID.randomUUID().toString();
            String paymentIntentId = "pi_998877";
            String reason = "EXPIRED_RESERVATION";

            outboxWriter.publishCancelPaymentCommand(orderId, causationMessageId, paymentIntentId, reason);

            OutboxEvent event = findAndAssertSingleOutboxEvent(orderId, "payment.commands", "CancelPaymentCommand");
            CancelPaymentCommand decoded = deserialize(event.getPayload(), CancelPaymentCommand.class);

            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(decoded.getReason()).isEqualTo(reason);
            assertThat(decoded.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(decoded.getMetadata().getCausationId()).isEqualTo(causationMessageId);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Transactional Integrity")
    class TransactionalIntegrity {

        @Test
        @DisplayName("publishing without an active transaction throws IllegalTransactionStateException")
        void nonTransactionalCall_throwsIllegalTransactionStateException() {
            UUID orderId = UUID.randomUUID();

            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, "user@example.com", 1000L))
                  .isInstanceOf(IllegalTransactionStateException.class);

            assertThatThrownBy(() -> outboxWriter.publishReleaseInventoryCommand(orderId, UUID.randomUUID().toString(), "cart-1"))
                  .isInstanceOf(IllegalTransactionStateException.class);

            assertThatThrownBy(() -> outboxWriter.publishExtendReservationCommand(orderId, UUID.randomUUID().toString(), "cart-1"))
                  .isInstanceOf(IllegalTransactionStateException.class);

            assertThatThrownBy(() -> outboxWriter.publishCancelPaymentCommand(orderId, UUID.randomUUID().toString(), "pi_123", "REASON"))
                  .isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    private OutboxEvent findAndAssertSingleOutboxEvent(UUID orderId, String aggregateType, String eventType) {
        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> rows = entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              )
              .setParameter("orderId", orderId.toString())
              .setParameter("aggregateType", aggregateType)
              .getResultList();

        assertThat(rows).hasSize(1);
        OutboxEvent event = rows.getFirst();
        assertThat(event.getEventType()).isEqualTo(eventType);
        assertThat(event.getPayload()).isNotEmpty();
        return event;
    }

    private <T extends Message> T deserialize(byte[] payload, Class<T> targetClass) {
        Map<String, Object> deserializerConfig = new HashMap<>();
        deserializerConfig.put("schema.registry.url",
              "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        deserializerConfig.put("specific.protobuf.value.type", targetClass);

        try (KafkaProtobufDeserializer<T> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(deserializerConfig, false);
            return deserializer.deserialize("outbox-serialization-context", payload);
        }
    }
}