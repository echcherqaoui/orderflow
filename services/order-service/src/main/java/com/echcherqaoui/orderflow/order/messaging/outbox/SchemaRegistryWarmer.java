package com.echcherqaoui.orderflow.order.messaging.outbox;

import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ExtendReservationCommand;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ReleaseInventoryCommand;
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
        warm("orderflow.payment.commands", ChargePaymentCommand.getDefaultInstance());
        warm("orderflow.payment.commands", CancelPaymentCommand.getDefaultInstance());
        warm("orderflow.inventory.commands", ReleaseInventoryCommand.getDefaultInstance());
        warm("orderflow.inventory.commands", ExtendReservationCommand.getDefaultInstance());
    }
}