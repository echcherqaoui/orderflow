package com.echcherqaoui.orderflow.inventory.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ExtendReservationCommand;
import com.echcherqaoui.orderflow.inventory.service.ReservationService;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExtendReservationCommandHandler implements EventHandler<ExtendReservationCommand> {

    private final ReservationService reservationService;

    @Override
    public String getDescriptorFullName() {
        return ExtendReservationCommand.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull ExtendReservationCommand event,
                                    @NonNull SignatureService signatureService) {
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
    public void handle(@NonNull ExtendReservationCommand command) {
        reservationService.extendReservation(
              command.getCartId(),
              command.getOrderId(),
              command.getMetadata().getCorrelationId(),
              command.getMetadata().getMessageId()
        );
    }
}
