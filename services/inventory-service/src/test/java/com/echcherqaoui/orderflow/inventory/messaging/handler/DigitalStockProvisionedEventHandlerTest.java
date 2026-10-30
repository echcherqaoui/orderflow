package com.echcherqaoui.orderflow.inventory.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.inventory.service.ItemService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class DigitalStockProvisionedEventHandlerTest {

    @Mock
    private ItemService itemService;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private DigitalStockProvisionedEventHandler eventHandler;

    private final String itemName = "prod-123";
    private final UUID itemId = UUID.randomUUID();
    private final long priceCents = 1500L;
    private final int countAdded = 10;
    private final String messageId = UUID.randomUUID().toString();
    private final String correlationId = "corr-456";
    private final String signature = "sig-xyz-789";
    private final Instant now = Instant.now();

    private DigitalStockProvisionedEvent event;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(correlationId)
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        event = DigitalStockProvisionedEvent.newBuilder()
              .setMetadata(metadata)
              .setItemId(itemId.toString())
              .setItemName(itemName)
              .setPriceCents(priceCents)
              .setCountAdded(countAdded)
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = eventHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(DigitalStockProvisionedEvent.getDescriptor().getFullName());
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
                  String.valueOf(priceCents),
                  String.valueOf(countAdded)
            )).willReturn(true);

            boolean isValid = eventHandler.isSignatureValid(event, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  correlationId,
                  String.valueOf(now.getEpochSecond()),
                  String.valueOf(priceCents),
                  String.valueOf(countAdded)
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
                  String.valueOf(priceCents),
                  String.valueOf(countAdded)
            )).willReturn(false);

            boolean isValid = eventHandler.isSignatureValid(event, signatureService);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("successful execution delegates to item service")
        void handle_success_delegatesToItemService() {
            eventHandler.handle(event);

            then(itemService).should().provisionStock(
                  UUID.fromString(messageId),
                  itemId,
                  itemName,
                  priceCents,
                  countAdded
            );
        }

        @Test
        @DisplayName("duplicate message DataIntegrityViolationException is caught and skipped gracefully")
        void handle_duplicateMessage_catchesExceptionAndSkips() {
            doThrow(new DataIntegrityViolationException("Duplicate key"))
                  .when(itemService)
                  .provisionStock(
                        UUID.fromString(messageId),
                        itemId,
                        itemName,
                        priceCents,
                        countAdded
                  );

            assertThatCode(() -> eventHandler.handle(event)).doesNotThrowAnyException();

            then(itemService).should().provisionStock(
                  UUID.fromString(messageId),
                  itemId,
                  itemName,
                  priceCents,
                  countAdded
            );
        }
    }
}