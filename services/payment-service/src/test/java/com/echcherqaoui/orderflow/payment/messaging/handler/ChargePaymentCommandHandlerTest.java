package com.echcherqaoui.orderflow.payment.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.ChargePaymentCommand;
import com.echcherqaoui.orderflow.payment.service.PaymentInitializationService;
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
class ChargePaymentCommandHandlerTest {

    @Mock
    private PaymentInitializationService paymentInitializationService;

    @Mock
    private SignatureService signatureService;

    @InjectMocks
    private ChargePaymentCommandHandler commandHandler;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-123";
    private final long totalPriceCents = 7500L;
    private final String messageId = "msg-" + UUID.randomUUID();
    private final String signature = "sig-abc-123";
    private final Instant now = Instant.now();

    private ChargePaymentCommand command;

    @BeforeEach
    void setUp() {
        MessageMetadata metadata = MessageMetadata.newBuilder()
              .setMessageId(messageId)
              .setCorrelationId(orderId.toString())
              .setSignature(signature)
              .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
              .build();

        command = ChargePaymentCommand.newBuilder()
              .setMetadata(metadata)
              .setUserId(userId)
              .setTotalPriceCents(totalPriceCents)
              .build();
    }

    @Nested
    @DisplayName("getDescriptorFullName()")
    class GetDescriptorFullName {

        @Test
        @DisplayName("returns correct protobuf descriptor full name")
        void getDescriptorFullName_returnsExpectedDescriptor() {
            String descriptor = commandHandler.getDescriptorFullName();
            assertThat(descriptor).isEqualTo(ChargePaymentCommand.getDescriptor().getFullName());
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
                  String.valueOf(now.getEpochSecond()),
                  orderId.toString(),
                  userId,
                  String.valueOf(totalPriceCents)
            )).willReturn(true);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isTrue();
            then(signatureService).should().verify(
                  signature,
                  messageId,
                  String.valueOf(now.getEpochSecond()),
                  orderId.toString(),
                  userId,
                  String.valueOf(totalPriceCents)
            );
        }

        @Test
        @DisplayName("invalid signature delegates to signature service and returns false")
        void isSignatureValid_invalidSignature_returnsFalse() {
            given(signatureService.verify(
                  signature,
                  messageId,
                  String.valueOf(now.getEpochSecond()),
                  orderId.toString(),
                  userId,
                  String.valueOf(totalPriceCents)
            )).willReturn(false);

            boolean isValid = commandHandler.isSignatureValid(command, signatureService);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("successful execution parses correlation id and delegates to initialization service")
        void handle_success_delegatesToInitializationService() {
            commandHandler.handle(command);

            then(paymentInitializationService).should().initializePayment(
                  orderId,
                  userId,
                  totalPriceCents,
                  messageId
            );
        }

        @Test
        @DisplayName("malformed correlation id throws IllegalArgumentException and aborts processing")
        void handle_invalidCorrelationId_throwsIllegalArgumentException() {
            MessageMetadata invalidMetadata = MessageMetadata.newBuilder()
                  .setMessageId(messageId)
                  .setCorrelationId("not-a-valid-uuid")
                  .setSignature(signature)
                  .build();

            ChargePaymentCommand invalidCommand = ChargePaymentCommand.newBuilder()
                  .setMetadata(invalidMetadata)
                  .setUserId(userId)
                  .setTotalPriceCents(totalPriceCents)
                  .build();

            assertThatThrownBy(() -> commandHandler.handle(invalidCommand))
                  .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(paymentInitializationService);
        }
    }
}