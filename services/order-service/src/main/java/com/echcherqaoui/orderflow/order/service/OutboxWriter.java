package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.ChargePaymentCommand;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.echcherqaoui.orderflow.util.InstantConverter;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.MANDATORY;

@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;

    private final SignatureService signatureService;

    private final KafkaProtobufSerializer<Message> serializer;

    private static final String SERIALIZATION_CONTEXT = "outbox-serialization-context";

    private void persist(@NonNull Message message,
                         String orderId,
                         String aggregateType) {
        // Serializes payload + attaches 5-byte Confluent header (Magic byte + Schema ID)
        byte[] payload = serializer.serialize(SERIALIZATION_CONTEXT, message);

        OutboxEvent event = new OutboxEvent()
              .setId(UUID.randomUUID())
              .setAggregateType(aggregateType)
              .setAggregateId(orderId)
              .setEventType(message.getClass().getSimpleName())
              .setPayload(payload)
              .setCreatedAt(Instant.now());

        outboxEventRepository.save(event);
    }

    @Transactional(propagation = MANDATORY)
    public void publishChargePaymentCommand(UUID orderId, String userId, long totalPriceCents) {
        String messageId = UUID.randomUUID().toString();
        Timestamp occurredAt = InstantConverter.toTimestamp(Instant.now());

        String signature = signatureService.sign(
              messageId,
              occurredAt.getSeconds(),
              orderId,
              userId,
              totalPriceCents
        );

        String orderIdString = orderId.toString();

        MessageMetadata messageMetadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderIdString)
              .setOccurredAt(occurredAt)
              .setSignature(signature)
              .build();

        ChargePaymentCommand command = ChargePaymentCommand.newBuilder()
              .setMetadata(messageMetadata)
              .setUserId(userId)
              .setOrderId(orderIdString)
              .setTotalPriceCents(totalPriceCents)
              .build();

        // Persist using orderId as the partitioning/routing key for the outbox
        persist(
              command,
              orderIdString,
              "payment.commands"
        );
    }
}
