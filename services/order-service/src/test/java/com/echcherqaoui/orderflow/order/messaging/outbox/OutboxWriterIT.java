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
import org.springframework.test.context.transaction.TestTransaction;
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

        @Test
        @Transactional
        @DisplayName("publishing multiple commands in single transaction persists all records sequentially")
        void publishingMultipleCommands_persistsAllInSameTransaction() {
            UUID orderId = UUID.randomUUID();
            String causationId = UUID.randomUUID().toString();

            outboxWriter.publishChargePaymentCommand(orderId, "user@example.com", 5000L);
            outboxWriter.publishReleaseInventoryCommand(orderId, causationId, "cart-multi");

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> events = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId ORDER BY e.createdAt ASC",
                        OutboxEvent.class
                  )
                  .setParameter("orderId", orderId.toString())
                  .getResultList();

            assertThat(events).hasSize(2);
            assertThat(events.get(0).getEventType()).isEqualTo("ChargePaymentCommand");
            assertThat(events.get(1).getEventType()).isEqualTo("ReleaseInventoryCommand");
        }
    }

    @Nested
    @DisplayName("Transactional Integrity")
    class TransactionalIntegrity {

        private final UUID orderId = UUID.randomUUID();
        private final String reservationId = UUID.randomUUID().toString();

        @Test
        @DisplayName("publishChargePaymentCommand without an active transaction throws IllegalTransactionStateException")
        void publishChargePaymentCommand_noTransaction_throwsException() {
            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, "user@example.com", 1000L))
                  .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @DisplayName("publishReleaseInventoryCommand without an active transaction throws IllegalTransactionStateException")
        void publishReleaseInventoryCommand_noTransaction_throwsException() {
            assertThatThrownBy(() -> outboxWriter.publishReleaseInventoryCommand(orderId, reservationId, "cart-1"))
                  .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @DisplayName("publishExtendReservationCommand without an active transaction throws IllegalTransactionStateException")
        void publishExtendReservationCommand_noTransaction_throwsException() {
            assertThatThrownBy(() -> outboxWriter.publishExtendReservationCommand(orderId, reservationId, "cart-1"))
                  .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @DisplayName("publishCancelPaymentCommand without an active transaction throws IllegalTransactionStateException")
        void publishCancelPaymentCommand_noTransaction_throwsException() {
            assertThatThrownBy(() -> outboxWriter.publishCancelPaymentCommand(orderId, reservationId, "pi_123", "REASON"))
                  .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @Transactional
        @DisplayName("transaction rollback discards written outbox entries")
        void transactionRollback_discardsOutboxEntries() {
            outboxWriter.publishChargePaymentCommand(orderId, "user@example.com", 2000L);
            entityManager.flush();

            TestTransaction.flagForRollback();
            TestTransaction.end();

            TestTransaction.start();
            List<OutboxEvent> rows = entityManager
                  .createQuery("SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId", OutboxEvent.class)
                  .setParameter("orderId", orderId.toString())
                  .getResultList();

            assertThat(rows).isEmpty();
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
        OutboxEvent event = rows.getFirst(); // Use rows.get(0) if below Java 21

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