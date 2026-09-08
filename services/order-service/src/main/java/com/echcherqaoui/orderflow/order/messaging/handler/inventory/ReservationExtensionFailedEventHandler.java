package com.echcherqaoui.orderflow.order.messaging.handler.inventory;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtensionFailedEvent;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.order.service.OrderSagaService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReservationExtensionFailedEventHandler implements EventHandler<ReservationExtensionFailedEvent> {

    private final OrderSagaService orderSagaService;

    @Override
    public String getDescriptorFullName() {
        return ReservationExtensionFailedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@lombok.NonNull ReservationExtensionFailedEvent event,
                                    @lombok.NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getCartId(),
              event.getReason()
        );
    }

    @Override
    public void handle(@NonNull ReservationExtensionFailedEvent event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        orderSagaService.handleReservationExtensionFailed(
              orderId,
              event.getMetadata().getMessageId(),
              event.getCartId(),
              event.getReason(),
              event.getMetadata().getMessageId()
        );
    }
}