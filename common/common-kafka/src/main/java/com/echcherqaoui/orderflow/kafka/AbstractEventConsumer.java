package com.echcherqaoui.orderflow.kafka;

import com.echcherqaoui.orderflow.exception.core.EventProcessingException;
import com.echcherqaoui.orderflow.exception.core.EventSecurityException;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.echcherqaoui.orderflow.exception.core.CommonErrorCode.DESERIALIZATION_FAILED;
import static com.echcherqaoui.orderflow.exception.core.CommonErrorCode.NO_HANDLER_FOUND;

@Slf4j
public abstract class AbstractEventConsumer {

    private final Map<String, EventHandler<?>> handlerMap;
    private final SignatureService signatureService;

    protected AbstractEventConsumer(@NonNull List<EventHandler<?>> handlers,
                                    @NonNull SignatureService signatureService) {
        this.handlerMap = handlers.stream()
              .collect(Collectors.toMap(EventHandler::getDescriptorFullName, h -> h));
        this.signatureService = signatureService;
    }

    protected void dispatch(@NonNull ConsumerRecord<String, Message> consumerRecord) {
        Message payload = consumerRecord.value();

        if (payload == null) {
            log.error("Deserialization failed. Received null payload on topic: {}, offset: {}",
                  consumerRecord.topic(), consumerRecord.offset());
            throw new EventProcessingException(DESERIALIZATION_FAILED, consumerRecord.offset());
        }

        String schemaFullName = payload.getDescriptorForType().getFullName();

        @SuppressWarnings("unchecked")
        EventHandler<Message> handler = (EventHandler<Message>) handlerMap.get(schemaFullName);

        if (handler == null) {
            log.error("No registered handler found matching schema descriptor '{}' at offset: {}",
                  schemaFullName, consumerRecord.offset());
            throw new EventProcessingException(NO_HANDLER_FOUND, schemaFullName, consumerRecord.offset());
        }

        if (!handler.isSignatureValid(payload, signatureService)) {
            log.error("HMAC signature verification failed for schema '{}' at offset: {}", schemaFullName, consumerRecord.offset());
            throw new EventSecurityException(schemaFullName);
        }

        log.debug("Dispatching event [{}] to handler [{}] at offset {}",
              schemaFullName, handler.getClass().getSimpleName(), consumerRecord.offset());

        handler.handle(payload);
    }
}