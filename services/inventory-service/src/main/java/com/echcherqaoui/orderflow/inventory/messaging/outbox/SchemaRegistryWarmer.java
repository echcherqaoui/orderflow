package com.echcherqaoui.orderflow.inventory.messaging.outbox;

import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryReleasedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtendedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtensionFailedEvent;
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
    private static final String INVENTORY_EVENTS_TOPIC = "orderflow.inventory.events";
    private final KafkaProtobufSerializer<Message> serializer;

    private void warm(Message message) {
        try {
            serializer.serialize(INVENTORY_EVENTS_TOPIC, message);
            log.info("Schema Registry warmed for topic {}", INVENTORY_EVENTS_TOPIC);
        } catch (Exception e) {
            log.warn("Schema Registry warm-up failed for topic {}", INVENTORY_EVENTS_TOPIC, e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        warm(ReservationExtendedEvent.getDefaultInstance());
        warm(ReservationExtensionFailedEvent.getDefaultInstance());
        warm(InventoryReleasedEvent.getDefaultInstance());
    }
}