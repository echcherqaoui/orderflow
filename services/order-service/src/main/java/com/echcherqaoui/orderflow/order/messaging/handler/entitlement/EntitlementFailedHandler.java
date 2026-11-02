package com.echcherqaoui.orderflow.order.messaging.handler.entitlement;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementFailedEvent;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.order.service.OrderSagaService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EntitlementFailedHandler implements EventHandler<EntitlementFailedEvent> {

    private final OrderSagaService orderSagaService;

    @Override
    public String getDescriptorFullName() {
        return EntitlementFailedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull EntitlementFailedEvent event,
                                    @NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getUserId(),
              event.getReason()
        );
    }

    @Override
    public void handle(@lombok.NonNull EntitlementFailedEvent event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        orderSagaService.handleEntitlementGrantFailed(
              orderId,
              event.getReason(),
              event.getMetadata().getMessageId()
        );
    }
}