package com.echcherqaoui.orderflow.entitlement.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.StockProvisionRejectedEvent;
import com.echcherqaoui.orderflow.entitlement.service.LicenseKeyService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class StockProvisionRejectedEventHandlerTest {

    @Mock
    private LicenseKeyService licenseKeyService;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private StockProvisionRejectedEventHandler eventHandler;

    private final String messageId = "msg-" + UUID.randomUUID();
    private final String correlationId = UUID.randomUUID().toString();
    private final String signature = "sig-abc-456";
    private final Instant now = Instant.now();
    private final String itemName = "prod-item-999";
    private final String reason = "Insufficient inventory stock";

    private StockProvisionRejectedEvent event;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(correlationId)
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        event = StockProvisionRejectedEvent.newBuilder()
              .setMetadata(metadata)
              .setItemName(itemName)
              .setReason(reason)
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = eventHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(StockProvisionRejectedEvent.getDescriptor().getFullName());
        }
    }

    @Nested
    @DisplayName("isSignatureValid()")
    class IsSignatureValid {

        @Test
        @DisplayName("valid signature delegates to signature service and returns true")
        void isSignatureValid_validSignature_returnsTrue() {
            given(signatureService.verify(
                  signature,
                  messageId,
                  correlationId,
                  String.valueOf(now.getEpochSecond()),
                  reason
            )).willReturn(true);

            boolean isValid = eventHandler.isSignatureValid(event, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  correlationId,
                  String.valueOf(now.getEpochSecond()),
                  reason
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
                  reason
            )).willReturn(false);

            boolean isValid = eventHandler.isSignatureValid(event, signatureService);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("successful execution delegates to license key service")
        void handle_success_delegatesToLicenseKeyService() {
            eventHandler.handle(event);

            then(licenseKeyService).should().rejectProvisioning(
                  itemName,
                  reason
            );
        }
    }
}