package com.echcherqaoui.orderflow.payment.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitializationFailedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitiatedEvent;
import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
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
import java.util.Objects;
import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.MANDATORY;

@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;

    private final SignatureService signatureService;

    private final KafkaProtobufSerializer<Message> serializer;

    private void persist(@NonNull Message message,
                         String orderId,
                         String aggregateType) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        String topic = "orderflow." + aggregateType;

        // Serializes payload + attaches 5-byte Confluent header (Magic byte + Schema ID)
        byte[] payload = serializer.serialize(topic, message);

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
    public void publishPaymentInitiatedEvent(@NonNull UUID orderId,
                                             String triggerEventId,
                                             @NonNull CreatePaymentIntentResponse pspResponse) {
        String messageId = UUID.randomUUID().toString();
        Timestamp occurredAt = InstantConverter.toTimestamp(Instant.now());
        String orderIdString = orderId.toString();

        String signature = signatureService.sign(
              messageId,
              orderId.toString(),
              pspResponse.paymentIntentId(),
              String.valueOf(occurredAt.getSeconds())
        );

        MessageMetadata messageMetadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderId.toString())
              .setCausationId(triggerEventId)
              .setOccurredAt(occurredAt)
              .setSignature(signature)
              .build();

        PaymentInitiatedEvent event = PaymentInitiatedEvent.newBuilder()
              .setMetadata(messageMetadata)
              .setPaymentIntentId(pspResponse.paymentIntentId())
              .setClientSecret(pspResponse.clientSecret())
              .build();

        // Persist using orderId as the partitioning/routing key for the outbox
        persist(
              event,
              orderIdString,
              "payment.events"
        );
    }

    @Transactional(propagation = MANDATORY)
    public void publishPaymentInitializationFailedEvent(UUID orderId,
                                                        String triggerEventId,
                                                        String reason) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        String messageId = UUID.randomUUID().toString();
        Timestamp occurredAt = InstantConverter.toTimestamp(Instant.now());
        String orderIdString = orderId.toString();

        String signature = signatureService.sign(
              messageId,
              orderIdString,
              reason,
              String.valueOf(occurredAt.getSeconds())
        );

        MessageMetadata messageMetadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderIdString)
              .setCausationId(triggerEventId)
              .setOccurredAt(occurredAt)
              .setSignature(signature)
              .build();

        PaymentInitializationFailedEvent event = PaymentInitializationFailedEvent.newBuilder()
              .setMetadata(messageMetadata)
              .setReason(reason)
              .build();

        persist(
              event,
              orderIdString,
              "payment.events"
        );
    }
}
