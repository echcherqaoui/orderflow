package com.echcherqaoui.orderflow.entitlement.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.commands.v1.GrantEntitlementCommand;
import com.echcherqaoui.orderflow.entitlement.exception.domain.KeyPoolExhaustedException;
import com.echcherqaoui.orderflow.entitlement.service.EntitlementAllocationService;
import com.echcherqaoui.orderflow.entitlement.service.EntitlementFailurePublisher;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class GrantEntitlementCommandHandler implements EventHandler<GrantEntitlementCommand> {

    private final EntitlementAllocationService allocationService;
    private final EntitlementFailurePublisher failurePublisher;

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
        UUID orderId = UUID.fromString(command.getOrderId());
        String userId = command.getUserId();
        String causationId = command.getMetadata().getCausationId();

        try {
            allocationService.allocate(
                  command.getItemIdsList(),
                  orderId,
                  userId,
                  causationId
            );
        } catch (KeyPoolExhaustedException e) {
            failurePublisher.publishFailure(
                  orderId,
                  userId,
                  causationId,
                  "KEY_POOL_EXHAUSTED"
            );
        } catch (DataIntegrityViolationException e) {
            log.warn("Order {} already allocated, skipping redelivery", orderId);
        }
    }
}

