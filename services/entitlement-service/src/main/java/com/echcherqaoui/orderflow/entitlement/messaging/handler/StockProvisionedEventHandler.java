package com.echcherqaoui.orderflow.entitlement.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.StockProvisionedEvent;
import com.echcherqaoui.orderflow.entitlement.service.LicenseKeyService;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockProvisionedEventHandler implements EventHandler<StockProvisionedEvent> {

    private final LicenseKeyService licenseKeyService;

    @Override
    public String getDescriptorFullName() {
        return StockProvisionedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull StockProvisionedEvent event,
                                    @NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              String.valueOf(event.getQuantity())
        );
    }

    @Override
    public void handle(@NonNull StockProvisionedEvent event) {
        licenseKeyService.completeProvisioning(
              event.getItemName(),
              event.getQuantity()
        );
    }
}

