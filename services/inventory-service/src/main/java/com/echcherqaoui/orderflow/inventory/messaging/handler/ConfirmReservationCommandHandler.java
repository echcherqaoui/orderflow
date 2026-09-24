package com.echcherqaoui.orderflow.inventory.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ConfirmReservationCommand;
import com.echcherqaoui.orderflow.inventory.service.ReservationService;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ConfirmReservationCommandHandler implements EventHandler<ConfirmReservationCommand> {

    private final ReservationService reservationService;

    @Override
    public String getDescriptorFullName() {
        return ConfirmReservationCommand.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull ConfirmReservationCommand event,
                                    @NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getOrderId(),
              event.getCartId()
        );
    }

    @Override
    public void handle(@NonNull ConfirmReservationCommand command) {
        reservationService.confirmReservation(
              command.getCartId(),
              UUID.fromString(command.getOrderId()),
              command.getMetadata().getMessageId()
        );
    }
}
