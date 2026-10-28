package com.echcherqaoui.orderflow.entitlement.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementGrantedEvent;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.transaction.annotation.Propagation.MANDATORY;

@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private static final String TOPIC_PREFIX = "orderflow.";
    private static final String SAGA_EVENTS = "entitlement.events";
    private static final String ITEM_EVENTS = "entitlement.item-events";
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
    public void publishDigitalStockProvisionedEvent(@lombok.NonNull UUID itemId,
                                                    @lombok.NonNull String itemName,
                                                    @lombok.NonNull Long priceCents,
                                                    @lombok.NonNull Integer count) {
        String itemIdStr = itemId.toString();
        MessageMetadata metadata = createMetadata(
              itemIdStr,
              null,
              priceCents.toString(),
              count.toString()
        );

        DigitalStockProvisionedEvent event = DigitalStockProvisionedEvent.newBuilder()
              .setMetadata(metadata)
              .setItemId(itemIdStr)
              .setItemName(itemName)
              .setPriceCents(priceCents)
              .setCountAdded(count)
              .build();

        persist(event, itemName, ITEM_EVENTS);
    }

    @Transactional(propagation = MANDATORY)
    public void writeEntitlementGranted(@lombok.NonNull UUID orderId,
                                        @lombok.NonNull String userId,
                                        @lombok.NonNull List<String> accessKeys,
                                        String triggerEventId) {
        String orderIdStr = orderId.toString();



        String[] signatureParams = Stream.concat(Stream.of(userId), accessKeys.stream())
              .toArray(String[]::new);

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              triggerEventId,
              signatureParams
        );

        EntitlementGrantedEvent event = EntitlementGrantedEvent.newBuilder()
              .setMetadata(metadata)
              .setOrderId(orderIdStr)
              .setUserId(userId)
              .addAllAccessKeys(accessKeys)
              .build();

        persist(event, orderIdStr, SAGA_EVENTS);
    }
}