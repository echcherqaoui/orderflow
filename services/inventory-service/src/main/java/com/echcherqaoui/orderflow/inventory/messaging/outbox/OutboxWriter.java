package com.echcherqaoui.orderflow.inventory.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryConfirmationFailedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryConfirmedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryReleasedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtendedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtensionFailedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.StockProvisionRejectedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.StockProvisionedEvent;
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

    private static final String TOPIC_PREFIX = "orderflow.";
    private static final String SAGA_EVENTS = "inventory.events";
    private static final String ITEM_EVENTS = "inventory.item-events";
    private static final int BASE_PARAM_COUNT = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final SignatureService signatureService;
    private final KafkaProtobufSerializer<Message> serializer;

    private void persist(@lombok.NonNull Message message,
                         @lombok.NonNull String aggregateId,
                         @lombok.NonNull String aggregateType) {
        String topic = TOPIC_PREFIX + aggregateType;
        byte[] payload = serializer.serialize(topic, message);

        OutboxEvent event = new OutboxEvent()
              .setId(UUID.randomUUID())
              .setAggregateType(aggregateType)
              .setAggregateId(aggregateId)
              .setEventType(message.getClass().getSimpleName())
              .setPayload(payload)
              .setCreatedAt(Instant.now());

        outboxEventRepository.save(event);
    }

    @NonNull
    private MessageMetadata createMetadata(String correlationId,
                                           String causationId,
                                           String... extraSignatureParams) {
        String messageId = UUID.randomUUID().toString();
        Timestamp occurredAt = InstantConverter.toTimestamp(Instant.now());

        int extraLength = (extraSignatureParams != null) ? extraSignatureParams.length : 0;
        String[] signatureParams = new String[BASE_PARAM_COUNT + extraLength];

        signatureParams[0] = messageId;
        signatureParams[1] = correlationId;
        signatureParams[2] = String.valueOf(occurredAt.getSeconds());

        if (extraLength > 0)
            System.arraycopy(extraSignatureParams, 0, signatureParams, BASE_PARAM_COUNT, extraLength);

        String signature = signatureService.sign(signatureParams);

        MessageMetadata.Builder builder = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(correlationId)
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

        persist(event, orderIdStr, SAGA_EVENTS);
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

        persist(event, orderIdStr, SAGA_EVENTS);
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

        persist(event, orderIdStr, SAGA_EVENTS);
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

        persist(event, orderIdStr, SAGA_EVENTS);
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

        persist(event, orderIdStr, SAGA_EVENTS);
    }

    @Transactional(propagation = MANDATORY)
    public void publishStockProvisionedEvent(@lombok.NonNull String causationId,
                                             @lombok.NonNull String itemName,
                                             int quantity) {

        MessageMetadata metadata = createMetadata(
              itemName,
              causationId,
              String.valueOf(quantity)
        );

        StockProvisionedEvent event = StockProvisionedEvent.newBuilder()
              .setMetadata(metadata)
              .setItemName(itemName)
              .setQuantity(quantity)
              .build();

        persist(event, itemName, ITEM_EVENTS);
    }

    @Transactional(propagation = MANDATORY)
    public void publishStockProvisionRejectedEvent(@lombok.NonNull String causationId,
                                                   @lombok.NonNull String itemName,
                                                   @lombok.NonNull String reason) {
        MessageMetadata metadata = createMetadata(
              itemName,
              causationId,
              reason
        );

        StockProvisionRejectedEvent event = StockProvisionRejectedEvent.newBuilder()
              .setMetadata(metadata)
              .setItemName(itemName)
              .setReason(reason)
              .build();

        persist(event, itemName, ITEM_EVENTS);
    }
}