package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EntitlementFailurePublisher {

    private final OutboxWriter outboxWriter;

    /**
     * Writes the failure event to the outbox in its own transaction.
     * REQUIRES_NEW: the allocation transaction has already rolled back, and this
     * row must commit regardless of any outer rollback-only transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishFailure(UUID orderId,
                               String userId,
                               String causationId,
                               String reason) {
        outboxWriter.writeEntitlementFailed(
              orderId,
              userId,
              reason,
              causationId
        );
    }
}