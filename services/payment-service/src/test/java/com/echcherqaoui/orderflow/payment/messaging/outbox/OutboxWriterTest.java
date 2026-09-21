package com.echcherqaoui.orderflow.payment.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentCancelledEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentChargedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentFailedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitializationFailedEvent;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitiatedEvent;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.google.protobuf.Message;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    private static final String EXPECTED_TOPIC = "orderflow.payment.events";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SignatureService signatureService;

    @Mock
    private KafkaProtobufSerializer<Message> serializer;

    @InjectMocks
    private OutboxWriter outboxWriter;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<Message> messageCaptor;

    @Captor
    private ArgumentCaptor<String[]> signatureParamsCaptor;

    private final UUID orderId = UUID.randomUUID();
    private final String causationId = UUID.randomUUID().toString();
    private final String dummySignature = "hmac-signature-12345";
    private final byte[] serializedPayload = new byte[]{0x0, 0x1, 0x2, 0x3};

    private CreatePaymentIntentResponse pspResponse;

    @BeforeEach
    void setUp() {
        pspResponse = new CreatePaymentIntentResponse("pi_123456", "client_secret_654321");
    }

    @Nested
    @DisplayName("publishPaymentInitiatedEvent()")
    class PublishPaymentInitiatedEvent {

        @Test
        @DisplayName("successful execution signs event, serializes protobuf message, and saves outbox event")
        void publishPaymentInitiatedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, causationId);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] capturedParams = signatureParamsCaptor.getValue();

            assertThat(capturedParams).hasSize(4);
            assertThat(capturedParams[0]).isNotBlank(); // messageId
            assertThat(capturedParams[1]).isEqualTo(orderId.toString());
            assertThat(Long.parseLong(capturedParams[2])).isGreaterThan(0L); // timestamp seconds
            assertThat(capturedParams[3]).isEqualTo(pspResponse.paymentIntentId());

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            Message capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage).isInstanceOf(PaymentInitiatedEvent.class);

            PaymentInitiatedEvent event = (PaymentInitiatedEvent) capturedMessage;
            assertThat(event.getPaymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
            assertThat(event.getClientSecret()).isEqualTo(pspResponse.clientSecret());
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedParams[0]);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getId()).isNotNull();
            assertThat(savedEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("PaymentInitiatedEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
            assertThat(savedEvent.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("null causationId builds metadata without setting causationId")
        void publishPaymentInitiatedEvent_nullCausationId_buildsMetadataWithoutCausationId() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);

            outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, null);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            PaymentInitiatedEvent event = (PaymentInitiatedEvent) messageCaptor.getValue();
            assertThat(event.getMetadata().getCausationId()).isEmpty();
        }

        @Test
        @DisplayName("null arguments throw NullPointerException")
        void publishPaymentInitiatedEvent_nullArguments_throwsException() {
            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(null, pspResponse, causationId))
                  .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(orderId, null, causationId))
                  .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void publishPaymentInitiatedEvent_signatureServiceFails_propagatesExceptionAndAborts() {
            RuntimeException signatureException = new RuntimeException("HMAC signing key error");
            given(signatureService.sign(any(String[].class))).willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, causationId))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("serializer failure propagates exception and halts outbox persistence")
        void publishPaymentInitiatedEvent_serializerFails_propagatesExceptionAndAbortsSave() {
            RuntimeException serializationException = new RuntimeException("Confluent Schema Registry timeout");
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willThrow(serializationException);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, causationId))
                  .isSameAs(serializationException);

            verifyNoInteractions(outboxEventRepository);
        }

        @Test
        @DisplayName("repository save failure propagates exception after message construction and serialization")
        void publishPaymentInitiatedEvent_repositorySaveFails_propagatesException() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class))).willThrow(dbException);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, causationId))
                  .isSameAs(dbException);
        }
    }

    @Nested
    @DisplayName("publishPaymentInitializationFailedEvent()")
    class PublishPaymentInitializationFailedEvent {

        private final String failureReason = "PSP connection failed: HTTP 503 Service Unavailable.";

        @Test
        @DisplayName("successful execution signs event, serializes protobuf message, and saves outbox event")
        void publishPaymentInitializationFailedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishPaymentInitializationFailedEvent(orderId, failureReason, causationId);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] capturedParams = signatureParamsCaptor.getValue();

            assertThat(capturedParams).hasSize(4);
            assertThat(capturedParams[1]).isEqualTo(orderId.toString());
            assertThat(capturedParams[3]).isEqualTo(failureReason);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            PaymentInitializationFailedEvent event = (PaymentInitializationFailedEvent) messageCaptor.getValue();

            assertThat(event.getFailureReason()).isEqualTo(failureReason);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(causationId);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getEventType()).isEqualTo("PaymentInitializationFailedEvent");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
        }

        @Test
        @DisplayName("null arguments throw NullPointerException")
        void publishPaymentInitializationFailedEvent_nullArguments_throwsException() {
            assertThatThrownBy(() -> outboxWriter.publishPaymentInitializationFailedEvent(null, failureReason, causationId))
                  .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitializationFailedEvent(orderId, null, causationId))
                  .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("publishPaymentCancelledEvent()")
    class PublishPaymentCancelledEvent {

        private final String paymentIntentId = "pi_987654";
        private final String reason = "User requested cancellation";

        @Test
        @DisplayName("successful execution with paymentIntentId signs event, serializes message, and saves outbox event")
        void publishPaymentCancelledEvent_withPaymentIntentId_success() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishPaymentCancelledEvent(orderId, paymentIntentId, reason, causationId);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] capturedParams = signatureParamsCaptor.getValue();

            assertThat(capturedParams).hasSize(5);
            assertThat(capturedParams[1]).isEqualTo(orderId.toString());
            assertThat(capturedParams[3]).isEqualTo(paymentIntentId);
            assertThat(capturedParams[4]).isEqualTo(reason);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            PaymentCancelledEvent event = (PaymentCancelledEvent) messageCaptor.getValue();

            assertThat(event.getOrderId()).isEqualTo(orderId.toString());
            assertThat(event.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(event.getReason()).isEqualTo(reason);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(causationId);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();
            assertThat(savedEvent.getEventType()).isEqualTo("PaymentCancelledEvent");
        }

        @Test
        @DisplayName("null mandatory arguments throw NullPointerException")
        void publishPaymentCancelledEvent_nullMandatoryArguments_throwsException() {
            assertThatThrownBy(() -> outboxWriter.publishPaymentCancelledEvent(null, paymentIntentId, reason, causationId))
                  .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> outboxWriter.publishPaymentCancelledEvent(orderId, null, reason, causationId))
                  .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> outboxWriter.publishPaymentCancelledEvent(orderId, paymentIntentId, null, causationId))
                  .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("writePaymentChargedEvent()")
    class WritePaymentChargedEvent {

        private final String orderIdStr = UUID.randomUUID().toString();
        private final String paymentIntentId = "pi_charged_123";

        @Test
        @DisplayName("successful execution signs event, serializes protobuf message, and saves outbox event")
        void writePaymentChargedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.writePaymentChargedEvent(orderIdStr, paymentIntentId);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] capturedParams = signatureParamsCaptor.getValue();

            assertThat(capturedParams).hasSize(4);
            assertThat(capturedParams[1]).isEqualTo(orderIdStr);
            assertThat(capturedParams[3]).isEqualTo(paymentIntentId);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            PaymentChargedEvent event = (PaymentChargedEvent) messageCaptor.getValue();

            assertThat(event.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderIdStr);
            assertThat(event.getMetadata().getCausationId()).isEmpty();

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getEventType()).isEqualTo("PaymentChargedEvent");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderIdStr);
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
        }

        @Test
        @DisplayName("null mandatory arguments throw NullPointerException")
        void writePaymentChargedEvent_nullArguments_throwsException() {
            assertThatThrownBy(() -> outboxWriter.writePaymentChargedEvent(null, paymentIntentId))
                  .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> outboxWriter.writePaymentChargedEvent(orderIdStr, null))
                  .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("writePaymentFailedEvent()")
    class WritePaymentFailedEvent {

        private final String orderIdStr = UUID.randomUUID().toString();
        private final String paymentIntentId = "pi_failed_123";
        private final String failureReason = "Card declined: Insufficient funds";

        @Test
        @DisplayName("successful execution signs event, serializes protobuf message, and saves outbox event")
        void writePaymentFailedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.writePaymentFailedEvent(orderIdStr, paymentIntentId, failureReason);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] capturedParams = signatureParamsCaptor.getValue();

            assertThat(capturedParams).hasSize(5);
            assertThat(capturedParams[1]).isEqualTo(orderIdStr);
            assertThat(capturedParams[3]).isEqualTo(paymentIntentId);
            assertThat(capturedParams[4]).isEqualTo(failureReason);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            PaymentFailedEvent event = (PaymentFailedEvent) messageCaptor.getValue();

            assertThat(event.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(event.getFailureReason()).isEqualTo(failureReason);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderIdStr);
            assertThat(event.getMetadata().getCausationId()).isEmpty();

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getEventType()).isEqualTo("PaymentFailedEvent");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderIdStr);
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
        }

        @Test
        @DisplayName("null failureReason falls back to default 'Payment failed' string")
        void writePaymentFailedEvent_nullFailureReason_usesDefaultMessage() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);

            outboxWriter.writePaymentFailedEvent(orderIdStr, paymentIntentId, null);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            PaymentFailedEvent event = (PaymentFailedEvent) messageCaptor.getValue();

            assertThat(event.getFailureReason()).isEqualTo("Payment failed");
        }

        @Test
        @DisplayName("null mandatory arguments throw NullPointerException")
        void writePaymentFailedEvent_nullMandatoryArguments_throwsException() {
            assertThatThrownBy(() -> outboxWriter.writePaymentFailedEvent(null, paymentIntentId, failureReason))
                  .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> outboxWriter.writePaymentFailedEvent(orderIdStr, null, failureReason))
                  .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }
}