package com.echcherqaoui.orderflow.order.messaging.handler.entitlement;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementGrantedEvent;
import com.echcherqaoui.orderflow.order.service.OrderSagaService;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class EntitlementGrantedHandlerTest {

    @Mock
    private OrderSagaService orderSagaService;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private EntitlementGrantedHandler eventHandler;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-123";
    private final List<String> accessKeys = List.of("key-1", "key-2");
    private final String messageId = "msg-" + UUID.randomUUID();
    private final String signature = "sig-abc-123";
    private final Instant now = Instant.now();

    private EntitlementGrantedEvent event;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderId.toString())
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        event = EntitlementGrantedEvent.newBuilder()
              .setMetadata(metadata)
              .setUserId(userId)
              .addAllAccessKeys(accessKeys)
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = eventHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(EntitlementGrantedEvent.getDescriptor().getFullName());
        }
    }

    @Nested
    @DisplayName("isSignatureValid()")
    class IsSignatureValid {

        @Test
        @DisplayName("valid signature delegates to signature service with correct parameter order and returns true")
        void isSignatureValid_validSignature_returnsTrue() {
            given(signatureService.verify(
                  signature,
                  messageId,
                  orderId.toString(),
                  String.valueOf(now.getEpochSecond()),
                  userId,
                  "key-1",
                  "key-2"
            )).willReturn(true);

            boolean isValid = eventHandler.isSignatureValid(event, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  orderId.toString(),
                  String.valueOf(now.getEpochSecond()),
                  userId,
                  "key-1",
                  "key-2"
            );
        }

        @Test
        @DisplayName("invalid signature delegates to signature service and returns false")
        void isSignatureValid_invalidSignature_returnsFalse() {
            given(signatureService.verify(
                  signature,
                  messageId,
                  orderId.toString(),
                  String.valueOf(now.getEpochSecond()),
                  userId,
                  "key-1",
                  "key-2"
            )).willReturn(false);

            boolean isValid = eventHandler.isSignatureValid(event, signatureService);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("successful execution delegates to order saga service")
        void handle_success_delegatesToOrderSagaService() {
            eventHandler.handle(event);

            then(orderSagaService).should().handleEntitlementGranted(
                  orderId,
                  messageId
            );
        }
    }
}