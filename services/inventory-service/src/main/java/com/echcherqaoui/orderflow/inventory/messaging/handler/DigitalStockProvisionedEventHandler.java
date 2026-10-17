package com.echcherqaoui.orderflow.inventory.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.inventory.service.ItemService;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DigitalStockProvisionedEventHandler implements EventHandler<DigitalStockProvisionedEvent> {

    private final ItemService itemService;

    @Override
    public String getDescriptorFullName() {
        return DigitalStockProvisionedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull DigitalStockProvisionedEvent event,
                                    @NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              String.valueOf(event.getPriceCents()),
              String.valueOf(event.getCountAdded())
        );
    }

    @Override
    public void handle(@NonNull DigitalStockProvisionedEvent event) {
        String messageId = event.getMetadata().getMessageId();
        try {
            itemService.provisionStock(
                  UUID.fromString(messageId),
                  event.getProductId(),
                  event.getPriceCents(),
                  event.getCountAdded()
            );
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate message {}, skipping", messageId);
        }
    }
}
