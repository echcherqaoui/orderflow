package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class EntitlementFailurePublisherTest {

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private EntitlementFailurePublisher failurePublisher;

    @Test
    @DisplayName("publishFailure delegates correctly to outboxWriter")
    void publishFailure_success_delegatesToOutboxWriter() {
        UUID orderId = UUID.randomUUID();
        String userId = "user-123";
        String causationId = UUID.randomUUID().toString();
        String reason = "KEY_POOL_EXHAUSTED";

        failurePublisher.publishFailure(orderId, userId, causationId, reason);

        then(outboxWriter).should().writeEntitlementFailed(
              orderId,
              userId,
              causationId,
              reason
        );
    }
}