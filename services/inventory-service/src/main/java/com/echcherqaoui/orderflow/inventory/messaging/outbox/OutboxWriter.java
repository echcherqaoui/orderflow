package com.echcherqaoui.orderflow.inventory.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryConfirmationFailedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryConfirmedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryReleasedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtendedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtensionFailedEvent;
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

    private static final String INVENTORY_EVENTS_TOPIC = "orderflow.inventory.events";
    private static final String AGGREGATE_TYPE = "inventory.events";
    private static final int BASE_PARAM_COUNT = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final SignatureService signatureService;
    private final KafkaProtobufSerializer<Message> serializer;

    private void persist(@lombok.NonNull Message message, @lombok.NonNull String orderId) {
        byte[] payload = serializer.serialize(INVENTORY_EVENTS_TOPIC, message);

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
    public void publishReservationExtendedEvent(@lombok.NonNull UUID orderId,
                                                String causationId,
                                                @lombok.NonNull String cartId,
                                                @lombok.NonNull Instant newExpiresAt) {
        String orderIdStr = orderId.toString();
        Timestamp expiresAtTs = InstantConverter.toTimestamp(newExpiresAt);

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              causationId,
              cartId,
              String.valueOf(expiresAtTs.getSeconds())
        );

        ReservationExtendedEvent event = ReservationExtendedEvent.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .setNewExpiresAt(expiresAtTs)
              .build();

        persist(event, orderIdStr);
    }

    @Transactional(propagation = MANDATORY)
    public void publishReservationExtensionFailedEvent(@lombok.NonNull UUID orderId,
                                                       String causationId,
                                                       @lombok.NonNull String cartId,
                                                       @lombok.NonNull String reason) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              causationId,
              cartId,
              reason
        );

        ReservationExtensionFailedEvent event = ReservationExtensionFailedEvent.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .setReason(reason)
              .build();

        persist(event, orderIdStr);
    }

    @Transactional(propagation = MANDATORY)
    public void publishInventoryReleasedEvent(@lombok.NonNull UUID orderId,
                                              String causationId,
                                              @lombok.NonNull String cartId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(orderIdStr, causationId, cartId);

        InventoryReleasedEvent event = InventoryReleasedEvent.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .build();

        persist(event, orderIdStr);
    }

    @Transactional(propagation = MANDATORY)
    public void publishInventoryConfirmedEvent(@lombok.NonNull UUID orderId,
                                               @lombok.NonNull String cartId,
                                               String causationId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(orderIdStr, causationId, cartId);

        InventoryConfirmedEvent event = InventoryConfirmedEvent.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .setOrderId(orderIdStr)
              .build();

        persist(event, orderIdStr);
    }

    @Transactional(propagation = MANDATORY)
    public void publishInventoryConfirmationFailedEvent(@lombok.NonNull UUID orderId,
                                                        String causationId,
                                                        @lombok.NonNull String cartId,
                                                        @lombok.NonNull String reason) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              causationId,
              cartId,
              reason
        );

        InventoryConfirmationFailedEvent event = InventoryConfirmationFailedEvent.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .setReason(reason)
              .build();

        persist(event, orderIdStr);
    }
}