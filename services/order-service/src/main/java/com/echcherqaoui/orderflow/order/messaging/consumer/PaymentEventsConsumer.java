package com.echcherqaoui.orderflow.order.messaging.consumer;

import com.echcherqaoui.orderflow.kafka.AbstractEventConsumer;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handles payment events (e.g., PaymentInitializationFailedEvent) produced by Payment Service.
 */
@Component
@Slf4j
public class PaymentEventsConsumer extends AbstractEventConsumer {

    public PaymentEventsConsumer(List<EventHandler<?>> handlers, SignatureService signatureService) {
        super(handlers, signatureService);
    }

    @KafkaListener(
          topics = "${orderflow.kafka.topics.payment-events}",
          groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(@lombok.NonNull ConsumerRecord<String, Message> consumerRecord,
                        @lombok.NonNull Acknowledgment ack) {
        dispatch(consumerRecord);

        ack.acknowledge();
    }
}