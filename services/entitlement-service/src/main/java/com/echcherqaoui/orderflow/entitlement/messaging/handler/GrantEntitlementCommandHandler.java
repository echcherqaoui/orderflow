package com.echcherqaoui.orderflow.entitlement.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.commands.v1.GrantEntitlementCommand;
import com.echcherqaoui.orderflow.entitlement.service.EntitlementAllocationService;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class GrantEntitlementCommandHandler implements EventHandler<GrantEntitlementCommand> {

    private final EntitlementAllocationService allocationService;

    @Override
    public String getDescriptorFullName() {
        return GrantEntitlementCommand.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull GrantEntitlementCommand command,
                                    @NonNull SignatureService signatureService) {
        MessageMetadata metadata = command.getMetadata();


        String[] signatureParams = Stream.concat(
              Stream.of(
                    metadata.getMessageId(),
                    metadata.getCorrelationId(),
                    String.valueOf(metadata.getOccurredAt().getSeconds()),
                    command.getUserId()
              ),
              command.getItemIdsList().stream()
        ).toArray(String[]::new);

        return signatureService.verify(
              metadata.getSignature(),
              signatureParams
        );
    }

    @Override
    public void handle(@NonNull GrantEntitlementCommand command) {
        allocationService.allocate(
              command.getItemIdsList(),
              UUID.fromString(command.getOrderId()),
              command.getUserId(),
              command.getMetadata().getCausationId()
        );
    }
}

