package com.echcherqaoui.orderflow.inventory.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ReleaseInventoryCommand;
import com.echcherqaoui.orderflow.inventory.service.ReservationService;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReleaseInventoryCommandHandler implements EventHandler<ReleaseInventoryCommand> {

    private final ReservationService reservationService;

    @Override
    public String getDescriptorFullName() {
        return ReleaseInventoryCommand.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull ReleaseInventoryCommand event,
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
    public void handle(@NonNull ReleaseInventoryCommand command) {
        reservationService.releaseReservation(
              command.getCartId(),
              command.getMetadata().getCorrelationId(),
              command.getMetadata().getMessageId()
        );
    }
}
