package com.echcherqaoui.orderflow.order.messaging.handler.inventory;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryReleasedEvent;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.order.service.OrderSagaService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class InventoryReleasedEventHandler implements EventHandler<InventoryReleasedEvent> {

    private final OrderSagaService orderSagaService;

    @Override
    public String getDescriptorFullName() {
        return InventoryReleasedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@lombok.NonNull InventoryReleasedEvent event,
                                    @lombok.NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getCartId()
        );
    }

    @Override
    public void handle(@lombok.NonNull InventoryReleasedEvent event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        orderSagaService.handleInventoryReleased(
              orderId,
              event.getCartId(),
              event.getMetadata().getMessageId()
        );
    }
}