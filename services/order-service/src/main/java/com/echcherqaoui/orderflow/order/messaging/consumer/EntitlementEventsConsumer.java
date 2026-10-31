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
 * Handles entitlement events produced by entitlement Service.
 */
@Component
@Slf4j
public class EntitlementEventsConsumer extends AbstractEventConsumer {

    public EntitlementEventsConsumer(List<EventHandler<?>> handlers, SignatureService signatureService) {
        super(handlers, signatureService);
    }

    @KafkaListener(
          topics = "${orderflow.kafka.topics.entitlement-events}",
          groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(@lombok.NonNull ConsumerRecord<String, Message> consumerRecord,
                        @lombok.NonNull Acknowledgment ack) {
        dispatch(consumerRecord);

        ack.acknowledge();
    }
}