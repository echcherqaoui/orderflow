package com.echcherqaoui.orderflow.payment.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.CancelPaymentCommand;
import com.echcherqaoui.orderflow.payment.service.PaymentService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CancelPaymentCommandHandlerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private CancelPaymentCommandHandler commandHandler;

    private final UUID orderId = UUID.randomUUID();
    private final String paymentIntentId = "pi_stripe_12345";
    private final String reason = "Customer requested cancellation";
    private final String messageId = "msg-" + UUID.randomUUID();
    private final String signature = "sig-abc-123";
    private final Instant now = Instant.now();

    private CancelPaymentCommand command;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderId.toString())
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        command = CancelPaymentCommand.newBuilder()
              .setMetadata(metadata)
              .setOrderId(orderId.toString())
              .setPaymentIntentId(paymentIntentId)
              .setReason(reason)
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = commandHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(CancelPaymentCommand.getDescriptor().getFullName());
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
                  paymentIntentId,
                  reason
            )).willReturn(true);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  orderId.toString(),
                  String.valueOf(now.getEpochSecond()),
                  paymentIntentId,
                  reason
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
                  paymentIntentId,
                  reason
            )).willReturn(false);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("successful execution parses order id and delegates to payment service")
        void handle_success_delegatesToPaymentService() {
            commandHandler.handle(command);

            then(paymentService).should().cancelPayment(
                  orderId,
                  paymentIntentId,
                  reason,
                  messageId
            );
        }

        @Test
        @DisplayName("malformed order id throws IllegalArgumentException and aborts processing")
        void handle_invalidOrderId_throwsIllegalArgumentException() {
            CancelPaymentCommand invalidCommand = CancelPaymentCommand.newBuilder()
                  .setMetadata(command.getMetadata())
                  .setOrderId("not-a-valid-uuid")
                  .setPaymentIntentId(paymentIntentId)
                  .setReason(reason)
                  .build();

            assertThatThrownBy(() -> commandHandler.handle(invalidCommand))
                  .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(paymentService);
        }
    }
}