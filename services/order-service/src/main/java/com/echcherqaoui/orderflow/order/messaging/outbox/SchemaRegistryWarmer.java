package com.echcherqaoui.orderflow.order.messaging.outbox;

import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ConfirmReservationCommand;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ExtendReservationCommand;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ReleaseInventoryCommand;
import com.echcherqaoui.orderflow.contracts.order.v1.OrderCancelledIntegrationEvent;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.CancelPaymentCommand;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.ChargePaymentCommand;
import com.google.protobuf.Message;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaRegistryWarmer {

    private static final String PAYMENT_COMMANDS_TOPIC = "orderflow.payment.commands";
    private static final String INVENTORY_COMMANDS_TOPIC = "orderflow.inventory.commands";
    private static final String ORDER_EVENTS_TOPIC = "orderflow.order.events";

    private final KafkaProtobufSerializer<Message> serializer;

    private void warm(String topic, Message message) {
        try {
            serializer.serialize(topic, message);
            log.info("Schema Registry warmed for topic {}", topic);
        } catch (Exception e) {
            log.warn("Schema Registry warm-up failed for topic {}", topic, e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        warm(PAYMENT_COMMANDS_TOPIC, ChargePaymentCommand.getDefaultInstance());
        warm(PAYMENT_COMMANDS_TOPIC, CancelPaymentCommand.getDefaultInstance());

        warm(INVENTORY_COMMANDS_TOPIC, ReleaseInventoryCommand.getDefaultInstance());
        warm(INVENTORY_COMMANDS_TOPIC, ExtendReservationCommand.getDefaultInstance());
        warm(INVENTORY_COMMANDS_TOPIC, ConfirmReservationCommand.getDefaultInstance());

        warm(ORDER_EVENTS_TOPIC, OrderCancelledIntegrationEvent.getDefaultInstance());
    }
}