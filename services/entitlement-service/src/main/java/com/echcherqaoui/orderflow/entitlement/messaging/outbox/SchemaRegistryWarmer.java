package com.echcherqaoui.orderflow.entitlement.messaging.outbox;

import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementFailedEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementGrantedEvent;
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

    private static final String ENTITLEMENT_EVENTS_TOPIC = "orderflow.entitlement.events";

    private final KafkaProtobufSerializer<Message> serializer;

    private void warm(Message message) {
        try {
            serializer.serialize(SchemaRegistryWarmer.ENTITLEMENT_EVENTS_TOPIC, message);
            log.info("Schema Registry warmed for topic {}", ENTITLEMENT_EVENTS_TOPIC);
        } catch (Exception e) {
            log.warn("Schema Registry warm-up failed for topic {}", ENTITLEMENT_EVENTS_TOPIC, e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        warm(DigitalStockProvisionedEvent.getDefaultInstance());
        warm(EntitlementGrantedEvent.getDefaultInstance());
        warm(EntitlementFailedEvent.getDefaultInstance());
    }
}