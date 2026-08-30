package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.ChargePaymentCommand;
import com.echcherqaoui.orderflow.order.AbstractIntegrationTest;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @Transactional
    @DisplayName("publishChargePaymentCommand writes a real Confluent-wire-format outbox row, decodable back into ChargePaymentCommand")
    void publishChargePaymentCommand_persistsRealSerializedPayload() {
        UUID orderId = UUID.randomUUID();
        String userId = "user@example.com";
        long totalPriceCents = 3299L;

        outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents);

        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> rows = entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              ).setParameter("orderId", orderId.toString())
              .setParameter("aggregateType", "payment.commands")
              .getResultList();

        assertThat(rows).hasSize(1);
        OutboxEvent event = rows.getFirst();
        assertThat(event.getEventType()).isEqualTo("ChargePaymentCommand");
        assertThat(event.getPayload()).isNotEmpty();

        // Decode the actual Confluent wire payload back into
        // the protobuf message, proving Schema Registry registration/lookup
        // and serialization genuinely worked — not just "bytes exist".
        Map<String, Object> deserializerConfig = new HashMap<>();
        deserializerConfig.put("schema.registry.url",
              "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        deserializerConfig.put("specific.protobuf.value.type", ChargePaymentCommand.class);

        try (KafkaProtobufDeserializer<ChargePaymentCommand> deserializer = new KafkaProtobufDeserializer<>()) {
            deserializer.configure(deserializerConfig, false);

            ChargePaymentCommand decoded = deserializer.deserialize("outbox-serialization-context", event.getPayload());

            assertThat(decoded.getOrderId()).isEqualTo(orderId.toString());
            assertThat(decoded.getUserId()).isEqualTo(userId);
            assertThat(decoded.getTotalPriceCents()).isEqualTo(totalPriceCents);
            assertThat(decoded.getMetadata().getSignature()).isNotBlank();
        }
    }

    @Test
    @DisplayName("publishChargePaymentCommand without an active transaction throws IllegalTransactionStateException")
    void publishChargePaymentCommand_noActiveTransaction_throwsIllegalTransactionStateException() {
        assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(UUID.randomUUID(), "user@example.com", 1000L))
              .isInstanceOf(IllegalTransactionStateException.class);
    }
}