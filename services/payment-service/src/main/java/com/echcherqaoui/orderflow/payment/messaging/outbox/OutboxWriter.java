package com.echcherqaoui.orderflow.payment.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentCancelledEvent;
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
import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.MANDATORY;

@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private static final String PAYMENT_EVENTS_TOPIC = "orderflow.payment.events";
    private static final String AGGREGATE_TYPE = "payment.events";
    private static final int BASE_PARAM_COUNT = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final SignatureService signatureService;
    private final KafkaProtobufSerializer<Message> serializer;

    private void persist(@lombok.NonNull Message message,
                         @lombok.NonNull String orderId) {
        byte[] payload = serializer.serialize(PAYMENT_EVENTS_TOPIC, message);

        OutboxEvent event = new OutboxEvent()
              .setId(UUID.randomUUID())
              .setAggregateType(AGGREGATE_TYPE)
              .setAggregateId(orderId)
              .setEventType(message.getClass().getSimpleName())
              .setPayload(payload)
              .setCreatedAt(Instant.now());

        outboxEventRepository.save(event);
    }

    @NonNull
    private MessageMetadata createMetadata(String orderIdStr,
                                           String causationId,
                                           String... extraSignatureParams) {
        String messageId = UUID.randomUUID().toString();
        Timestamp occurredAt = InstantConverter.toTimestamp(Instant.now());

        int extraLength = (extraSignatureParams != null) ? extraSignatureParams.length : 0;
        String[] signatureParams = new String[BASE_PARAM_COUNT + extraLength];

        signatureParams[0] = messageId;
        signatureParams[1] = orderIdStr;
        signatureParams[2] = String.valueOf(occurredAt.getSeconds());

        if (extraLength > 0)
            System.arraycopy(extraSignatureParams, 0, signatureParams, BASE_PARAM_COUNT, extraLength);

        String signature = signatureService.sign(signatureParams);

        MessageMetadata.Builder builder = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderIdStr)
              .setOccurredAt(occurredAt)
              .setSignature(signature);

        if (causationId != null)
            builder.setCausationId(causationId);

        return builder.build();
    }

    @Transactional(propagation = MANDATORY)
    public void publishPaymentInitiatedEvent(@lombok.NonNull UUID orderId,
                                             @lombok.NonNull CreatePaymentIntentResponse pspResponse,
                                             String causationId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              causationId,
              pspResponse.paymentIntentId()
        );

        PaymentInitiatedEvent event = PaymentInitiatedEvent.newBuilder()
              .setMetadata(metadata)
              .setPaymentIntentId(pspResponse.paymentIntentId())
              .setClientSecret(pspResponse.clientSecret())
              .build();

        persist(event, orderIdStr);
    }

    @Transactional(propagation = MANDATORY)
    public void publishPaymentInitializationFailedEvent(@lombok.NonNull UUID orderId,
                                                        @lombok.NonNull String reason,
                                                        String causationId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              causationId,
              reason
        );

        PaymentInitializationFailedEvent event = PaymentInitializationFailedEvent.newBuilder()
              .setMetadata(metadata)
              .setReason(reason)
              .build();

        persist(event, orderIdStr);
    }

    @Transactional(propagation = MANDATORY)
    public void publishPaymentCancelledEvent(@lombok.NonNull UUID orderId,
                                             @lombok.NonNull String paymentIntentId,
                                             @lombok.NonNull String reason,
                                             String causationId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              causationId,
              paymentIntentId,
              reason
        );

        PaymentCancelledEvent paymentCancelledEvent = PaymentCancelledEvent.newBuilder()
              .setMetadata(metadata)
              .setOrderId(orderIdStr)
              .setReason(reason)
              .setPaymentIntentId(paymentIntentId)
              .build();

        persist(paymentCancelledEvent, orderIdStr);
    }
}
