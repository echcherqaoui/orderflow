package com.echcherqaoui.orderflow.inventory.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ConfirmReservationCommand;
import com.echcherqaoui.orderflow.inventory.service.ReservationService;
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
class ConfirmReservationCommandHandlerTest {

    @Mock
    private ReservationService reservationService;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private ConfirmReservationCommandHandler commandHandler;

    private final UUID orderId = UUID.randomUUID();
    private final String cartId = "cart-123";
    private final String messageId = "msg-" + UUID.randomUUID();
    private final String signature = "sig-abc-123";
    private final Instant now = Instant.now();

    private ConfirmReservationCommand command;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderId.toString())
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        command = ConfirmReservationCommand.newBuilder()
              .setMetadata(metadata)
              .setOrderId(orderId.toString())
              .setCartId(cartId)
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = commandHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(ConfirmReservationCommand.getDescriptor().getFullName());
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
                  orderId.toString(),
                  cartId
            )).willReturn(true);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  orderId.toString(),
                  String.valueOf(now.getEpochSecond()),
                  orderId.toString(),
                  cartId
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
                  orderId.toString(),
                  cartId
            )).willReturn(false);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("successful execution delegates to reservation service")
        void handle_success_delegatesToReservationService() {
            commandHandler.handle(command);

            then(reservationService).should().confirmReservation(
                  cartId,
                  orderId,
                  messageId
            );
        }
    }
}