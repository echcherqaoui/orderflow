package com.echcherqaoui.orderflow.entitlement.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.commands.v1.GrantEntitlementCommand;
import com.echcherqaoui.orderflow.entitlement.exception.domain.KeyPoolExhaustedException;
import com.echcherqaoui.orderflow.entitlement.service.EntitlementAllocationService;
import com.echcherqaoui.orderflow.entitlement.service.EntitlementFailurePublisher;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class GrantEntitlementCommandHandlerTest {

    @Mock
    private EntitlementAllocationService allocationService;

    @Mock
    private EntitlementFailurePublisher failurePublisher;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private GrantEntitlementCommandHandler commandHandler;

    private final String messageId = "msg-" + UUID.randomUUID();
    private final String correlationId = UUID.randomUUID().toString();
    private final String causationId = UUID.randomUUID().toString();
    private final String signature = "sig-abc-123";
    private final Instant now = Instant.now();
    private final String userId = "user-123";
    private final String orderId = UUID.randomUUID().toString();
    private final List<String> itemIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());

    private GrantEntitlementCommand command;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(correlationId)
              .setCausationId(causationId)
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        command = GrantEntitlementCommand.newBuilder()
              .setMetadata(metadata)
              .setUserId(userId)
              .setOrderId(orderId)
              .addAllItemIds(itemIds)
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = commandHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(GrantEntitlementCommand.getDescriptor().getFullName());
        }
    }

    @Nested
    @DisplayName("isSignatureValid()")
    class IsSignatureValid {

        @Test
        @DisplayName("valid signature delegates to signature service with correct parameters and returns true")
        void isSignatureValid_validSignature_returnsTrue() {
            given(signatureService.verify(
                  signature,
                  messageId,
                  correlationId,
                  String.valueOf(now.getEpochSecond()),
                  userId,
                  itemIds.get(0),
                  itemIds.get(1)
            )).willReturn(true);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  correlationId,
                  String.valueOf(now.getEpochSecond()),
                  userId,
                  itemIds.get(0),
                  itemIds.get(1)
            );
        }

        @Test
        @DisplayName("invalid signature delegates to signature service and returns false")
        void isSignatureValid_invalidSignature_returnsFalse() {
            given(signatureService.verify(
                  signature,
                  messageId,
                  correlationId,
                  String.valueOf(now.getEpochSecond()),
                  userId,
                  itemIds.get(0),
                  itemIds.get(1)
            )).willReturn(false);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("successful execution delegates to allocation service with parsed parameters")
        void handle_success_delegatesToAllocationService() {
            commandHandler.handle(command);

            then(allocationService).should().allocate(
                  itemIds,
                  UUID.fromString(orderId),
                  userId,
                  causationId
            );
        }

        @Test
        @DisplayName("KeyPoolExhaustedException triggers failure publication")
        void handle_keyPoolExhausted_publishesFailure() {
            willThrow(new KeyPoolExhaustedException(UUID.randomUUID()))
                  .given(allocationService)
                  .allocate(itemIds, UUID.fromString(orderId), userId, causationId);

            commandHandler.handle(command);

            then(failurePublisher).should().publishFailure(
                  UUID.fromString(orderId),
                  userId,
                  causationId,
                  "KEY_POOL_EXHAUSTED"
            );
        }

        @Test
        @DisplayName("DataIntegrityViolationException is caught and skipped gracefully for idempotency")
        void handle_dataIntegrityViolation_catchesSilently() {
            willThrow(new DataIntegrityViolationException("duplicate unique constraint"))
                  .given(allocationService)
                  .allocate(itemIds, UUID.fromString(orderId), userId, causationId);

            assertThatCode(() -> commandHandler.handle(command))
                  .doesNotThrowAnyException();

            then(failurePublisher).shouldHaveNoInteractions();
        }
    }
}