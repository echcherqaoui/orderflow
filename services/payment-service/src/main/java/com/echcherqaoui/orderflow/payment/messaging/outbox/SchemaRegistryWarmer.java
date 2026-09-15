package com.echcherqaoui.orderflow.payment.messaging.outbox;

import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentCancelledEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitializationFailedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitiatedEvent;
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
    private static final String PAYMENT_EVENTS_TOPIC = "orderflow.payments.events";
    private final KafkaProtobufSerializer<Message> serializer;

    private void warm(Message message) {
        try {
            serializer.serialize(PAYMENT_EVENTS_TOPIC, message);
            log.info("Schema Registry warmed for topic {}", PAYMENT_EVENTS_TOPIC);
        } catch (Exception e) {
            log.warn("Schema Registry warm-up failed for topic {}", PAYMENT_EVENTS_TOPIC, e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        warm(PaymentInitiatedEvent.getDefaultInstance());
        warm(PaymentInitializationFailedEvent.getDefaultInstance());
        warm(PaymentCancelledEvent.getDefaultInstance());
    }
}