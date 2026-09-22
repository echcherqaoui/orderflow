package com.echcherqaoui.orderflow.order.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ConfirmReservationCommand;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ExtendReservationCommand;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ReleaseInventoryCommand;
import com.echcherqaoui.orderflow.contracts.order.v1.OrderCancelledIntegrationEvent;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.CancelPaymentCommand;
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

    private static final String TOPIC_PREFIX = "orderflow.";
    private static final String PAYMENT_COMMANDS_AGGREGATE = "payment.commands";
    private static final String INVENTORY_COMMANDS_AGGREGATE = "inventory.commands";
    private static final String ORDER_EVENTS_AGGREGATE = "order.events";
    private static final int BASE_PARAM_COUNT = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final SignatureService signatureService;
    private final KafkaProtobufSerializer<Message> serializer;

    private void persist(@lombok.NonNull Message message,
                         @lombok.NonNull String orderId,
                         @lombok.NonNull String aggregateType) {
        String topic = TOPIC_PREFIX + aggregateType;
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
    public void publishChargePaymentCommand(@lombok.NonNull UUID orderId,
                                            @lombok.NonNull String userId,
                                            long totalPriceCents) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(
              orderIdStr,
              null,
              userId,
              String.valueOf(totalPriceCents)
        );

        ChargePaymentCommand command = ChargePaymentCommand.newBuilder()
              .setMetadata(metadata)
              .setUserId(userId)
              .setOrderId(orderIdStr)
              .setTotalPriceCents(totalPriceCents)
              .build();

        persist(command, orderIdStr, PAYMENT_COMMANDS_AGGREGATE);
    }

    @Transactional(propagation = MANDATORY)
    public void publishReleaseInventoryCommand(@lombok.NonNull UUID orderId,
                                               String messageId,
                                               @lombok.NonNull String cartId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(orderIdStr, messageId, cartId);

        ReleaseInventoryCommand command = ReleaseInventoryCommand.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .build();

        persist(command, orderIdStr, INVENTORY_COMMANDS_AGGREGATE);
    }

    @Transactional(propagation = MANDATORY)
    public void publishExtendReservationCommand(@lombok.NonNull UUID orderId,
                                                String messageId,
                                                @lombok.NonNull String cartId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(orderIdStr, messageId,  cartId);

        ExtendReservationCommand command = ExtendReservationCommand.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .setOrderId(orderIdStr)
              .build();

        persist(command, orderIdStr, INVENTORY_COMMANDS_AGGREGATE);
    }

    @Transactional(propagation = MANDATORY)
    public void publishCancelPaymentCommand(@lombok.NonNull UUID orderId,
                                            String messageId,
                                            @lombok.NonNull String paymentIntentId,
                                            @lombok.NonNull String reason) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(orderIdStr, messageId, paymentIntentId, reason);

        CancelPaymentCommand command = CancelPaymentCommand.newBuilder()
              .setMetadata(metadata)
              .setOrderId(orderIdStr)
              .setPaymentIntentId(paymentIntentId)
              .setReason(reason)
              .build();

        persist(command, orderIdStr, PAYMENT_COMMANDS_AGGREGATE);
    }

    @Transactional(propagation = MANDATORY)
    public void publishOrderCancelledEvent(@lombok.NonNull UUID orderId,
                                           String triggerEventId,
                                           @lombok.NonNull String reason) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(orderIdStr, triggerEventId, reason);

        OrderCancelledIntegrationEvent event = OrderCancelledIntegrationEvent.newBuilder()
              .setMetadata(metadata)
              .setOrderId(orderIdStr)
              .setReason(reason)
              .build();

            persist(event, orderIdStr, ORDER_EVENTS_AGGREGATE);
    }

    @Transactional(propagation = MANDATORY)
    public void publishConfirmReservationCommand(@lombok.NonNull UUID orderId,
                                                 @lombok.NonNull String triggerEventId,
                                                 @lombok.NonNull String cartId) {
        String orderIdStr = orderId.toString();

        MessageMetadata metadata = createMetadata(orderIdStr, triggerEventId, cartId);

        ConfirmReservationCommand command = ConfirmReservationCommand.newBuilder()
              .setMetadata(metadata)
              .setOrderId(orderIdStr)
              .setCartId(cartId)
              .build();

        persist(command, orderIdStr, INVENTORY_COMMANDS_AGGREGATE);
    }
}