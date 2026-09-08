package com.echcherqaoui.orderflow.order.messaging.handler.inventory;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtendedEvent;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ReservationExtendedEventHandlerTest {

    @Mock
    private OrderSagaService orderSagaService;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private ReservationExtendedEventHandler eventHandler;

    private final UUID orderId = UUID.randomUUID();
    private final String cartId = "cart-123";
    private final String messageId = "msg-" + UUID.randomUUID();
    private final String signature = "sig-abc-123";
    private final Instant now = Instant.now();
    private final Instant newExpiresAt = now.plusSeconds(900);

    private ReservationExtendedEvent event;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderId.toString())
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        event = ReservationExtendedEvent.newBuilder()
              .setMetadata(metadata)
              .setCartId(cartId)
              .setNewExpiresAt(Timestamp.newBuilder().setSeconds(newExpiresAt.getEpochSecond()).build())
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = eventHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(ReservationExtendedEvent.getDescriptor().getFullName());
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
                  orderId.toString(),
                  String.valueOf(now.getEpochSecond()),
                  cartId,
                  String.valueOf(newExpiresAt.getEpochSecond())
            )).willReturn(true);

            boolean isValid = eventHandler.isSignatureValid(event, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  orderId.toString(),
                  String.valueOf(now.getEpochSecond()),
                  cartId,
                  String.valueOf(newExpiresAt.getEpochSecond())
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
                  cartId,
                  String.valueOf(newExpiresAt.getEpochSecond())
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

            then(orderSagaService).should().handleReservationExtended(
                  orderId,
                  cartId,
                  Instant.ofEpochSecond(newExpiresAt.getEpochSecond()),
                  messageId
            );
        }
    }
}