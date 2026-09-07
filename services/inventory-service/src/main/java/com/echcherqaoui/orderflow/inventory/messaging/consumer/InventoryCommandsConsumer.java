package com.echcherqaoui.orderflow.inventory.messaging.consumer;

import com.echcherqaoui.orderflow.kafka.AbstractEventConsumer;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.NonNull;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handles InventoryCommands produced by Order Service into orders.outbox
 */
@Component
@Slf4j
public class InventoryCommandsConsumer extends AbstractEventConsumer {

    public InventoryCommandsConsumer(List<EventHandler<?>> handlers,
                                     SignatureService signatureService) {
        super(handlers, signatureService);
    }

    @KafkaListener(
          topics = "${orderflow.kafka.topics.inventory-commands}",
          groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(@NonNull ConsumerRecord<String, Message> consumerRecord,
                        @NonNull Acknowledgment ack) {
        dispatch(consumerRecord);

        ack.acknowledge();
    }
}