package com.echcherqaoui.orderflow.payment.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitiatedEvent;
import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
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
    private ArgumentCaptor<String> messageIdCaptor;

    @Captor
    private ArgumentCaptor<String> secondsCaptor;

    private final UUID orderId = UUID.randomUUID();
    private final String triggerEventId = UUID.randomUUID().toString();
    private final String dummySignature = "hmac-signature-12345";
    private final byte[] serializedPayload = new byte[]{0x0, 0x1, 0x2, 0x3};

    private CreatePaymentIntentResponse pspResponse;

    @BeforeEach
    void setUp() {
        pspResponse = new CreatePaymentIntentResponse("pi_123456", "client_secret_654321");
    }

    private static String anyStringOrVarargs() {
        return any(String.class);
    }

    @Nested
    @DisplayName("publishPaymentInitiatedEvent()")
    class PublishPaymentInitiatedEvent {

        @Test
        @DisplayName("successful execution signs event, serializes protobuf message, and saves outbox event")
        void publishPaymentInitiatedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(
                  anyStringOrVarargs(),
                  eq(orderId.toString()),
                  eq(pspResponse.paymentIntentId()),
                  anyStringOrVarargs()
            )).willReturn(dummySignature);

            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse);

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  eq(orderId.toString()),
                  eq(pspResponse.paymentIntentId()),
                  secondsCaptor.capture()
            );

            String capturedMessageId = messageIdCaptor.getValue();
            long capturedSeconds = Long.parseLong(secondsCaptor.getValue());

            assertThat(capturedMessageId).isNotBlank();
            assertThat(UUID.fromString(capturedMessageId)).isNotNull();
            assertThat(capturedSeconds).isGreaterThan(0L);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            Message capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage).isInstanceOf(PaymentInitiatedEvent.class);

            PaymentInitiatedEvent event = (PaymentInitiatedEvent) capturedMessage;
            assertThat(event.getPaymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
            assertThat(event.getClientSecret()).isEqualTo(pspResponse.clientSecret());
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(triggerEventId);
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);
            assertThat(event.getMetadata().getOccurredAt().getSeconds()).isEqualTo(capturedSeconds);

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
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void publishPaymentInitiatedEvent_signatureServiceFails_propagatesExceptionAndAborts() {
            RuntimeException signatureException = new RuntimeException("HMAC signing key error");

            given(signatureService.sign(any(), any(), any(), any()))
                  .willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("serializer failure propagates exception and halts outbox persistence")
        void publishPaymentInitiatedEvent_serializerFails_propagatesExceptionAndAbortsSave() {
            RuntimeException serializationException = new RuntimeException("Confluent Schema Registry timeout");
            given(signatureService.sign(any(), any(), any(), any()))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class)))
                  .willThrow(serializationException);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse))
                  .isSameAs(serializationException);

            then(signatureService).should().sign(any(), any(), any(), any());
            then(serializer).should().serialize(eq(EXPECTED_TOPIC), any(Message.class));
            verifyNoInteractions(outboxEventRepository);
        }

        @Test
        @DisplayName("repository save failure propagates exception after message construction and serialization")
        void publishPaymentInitiatedEvent_repositorySaveFails_propagatesException() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");
            given(signatureService.sign(any(), any(), any(), any()))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willThrow(dbException);

            assertThatThrownBy(() -> outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse))
                  .isSameAs(dbException);

            then(signatureService).should().sign(any(), any(), any(), any());
            then(serializer).should().serialize(eq(EXPECTED_TOPIC), any(Message.class));
            then(outboxEventRepository).should().save(any(OutboxEvent.class));
        }
    }
}