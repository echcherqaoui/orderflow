package com.echcherqaoui.orderflow.order.messaging.handler.inventory;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtendedEvent;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.order.service.OrderSagaService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.echcherqaoui.orderflow.util.InstantConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReservationExtendedEventHandler implements EventHandler<ReservationExtendedEvent> {

    private final OrderSagaService orderSagaService;

    @Override
    public String getDescriptorFullName() {
        return ReservationExtendedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@lombok.NonNull ReservationExtendedEvent event,
                                    @lombok.NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getCartId(),
              String.valueOf(event.getNewExpiresAt().getSeconds())
        );
    }

    @Override
    public void handle(@lombok.NonNull ReservationExtendedEvent event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        orderSagaService.handleReservationExtended(
              orderId,
              event.getCartId(),
              InstantConverter.toInstant(event.getNewExpiresAt()),
              event.getMetadata().getMessageId()
        );
    }
}