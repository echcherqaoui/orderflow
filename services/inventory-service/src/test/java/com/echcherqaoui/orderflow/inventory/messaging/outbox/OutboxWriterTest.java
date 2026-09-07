package com.echcherqaoui.orderflow.inventory.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.InventoryReleasedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtendedEvent;
import com.echcherqaoui.orderflow.contracts.inventory.events.v1.ReservationExtensionFailedEvent;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.echcherqaoui.orderflow.util.InstantConverter;
import com.google.protobuf.Message;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    private static final String EXPECTED_TOPIC = "orderflow.inventory.events";
    private static final String AGGREGATE_TYPE = "inventory.events";

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
    private final String causationId = "msg-causation-123";
    private final String cartId = "cart-456";
    private final Instant newExpiresAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

    private final String dummySignature = "hmac-signature-67890";
    private final byte[] serializedPayload = new byte[]{0x4, 0x5, 0x6, 0x7};

    @Nested
    @DisplayName("publishReservationExtendedEvent()")
    class PublishReservationExtendedEvent {

        @Test
        @DisplayName("successful invocation creates event, signs metadata with expiration timestamp, and persists outbox event")
        void publishReservationExtendedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);

            outboxWriter.publishReservationExtendedEvent(orderId, causationId, cartId, newExpiresAt);

            long expectedExpiresAtSeconds = InstantConverter.toTimestamp(newExpiresAt).getSeconds();

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  eq(orderId.toString()),
                  secondsCaptor.capture(),
                  eq(cartId),
                  eq(String.valueOf(expectedExpiresAtSeconds))
            );

            String capturedMessageId = messageIdCaptor.getValue();
            long capturedSeconds = Long.parseLong(secondsCaptor.getValue());

            assertThat(capturedMessageId).isNotNull();
            assertThat(UUID.fromString(capturedMessageId)).isNotNull();
            assertThat(capturedSeconds).isGreaterThan(0L);

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            Message capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage).isInstanceOf(ReservationExtendedEvent.class);

            ReservationExtendedEvent event = (ReservationExtendedEvent) capturedMessage;
            assertThat(event.getCartId()).isEqualTo(cartId);
            assertThat(event.getNewExpiresAt().getSeconds()).isEqualTo(expectedExpiresAtSeconds);
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getId()).isNotNull();
            assertThat(savedEvent.getAggregateType()).isEqualTo(AGGREGATE_TYPE);
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("ReservationExtendedEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
            assertThat(savedEvent.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void publishReservationExtendedEvent_signatureFails_propagatesException() {
            RuntimeException signatureException = new RuntimeException("HMAC signing error");
            given(signatureService.sign(any(String[].class))).willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.publishReservationExtendedEvent(orderId, causationId, cartId, newExpiresAt))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("publishReservationExtensionFailedEvent()")
    class PublishReservationExtensionFailedEvent {

        @Test
        @DisplayName("successful invocation signs command with reason, serializes event, and saves to outbox")
        void publishReservationExtensionFailedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);

            String failureReason = "RESERVATION_NOT_FOUND";
            outboxWriter.publishReservationExtensionFailedEvent(orderId, causationId, cartId, failureReason);

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  eq(orderId.toString()),
                  secondsCaptor.capture(),
                  eq(cartId),
                  eq(failureReason)
            );

            String capturedMessageId = messageIdCaptor.getValue();

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            ReservationExtensionFailedEvent event = (ReservationExtensionFailedEvent) messageCaptor.getValue();

            assertThat(event.getCartId()).isEqualTo(cartId);
            assertThat(event.getReason()).isEqualTo(failureReason);
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getAggregateType()).isEqualTo(AGGREGATE_TYPE);
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("ReservationExtensionFailedEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
        }
    }

    @Nested
    @DisplayName("publishInventoryReleasedEvent()")
    class PublishInventoryReleasedEvent {

        @Test
        @DisplayName("successful invocation creates InventoryReleasedEvent, signs metadata, and saves outbox event")
        void publishInventoryReleasedEvent_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(EXPECTED_TOPIC), any(Message.class))).willReturn(serializedPayload);

            outboxWriter.publishInventoryReleasedEvent(orderId, causationId, cartId);

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  eq(orderId.toString()),
                  secondsCaptor.capture(),
                  eq(cartId)
            );

            String capturedMessageId = messageIdCaptor.getValue();

            then(serializer).should().serialize(eq(EXPECTED_TOPIC), messageCaptor.capture());
            InventoryReleasedEvent event = (InventoryReleasedEvent) messageCaptor.getValue();

            assertThat(event.getCartId()).isEqualTo(cartId);
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(causationId);
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getAggregateType()).isEqualTo(AGGREGATE_TYPE);
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("InventoryReleasedEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
        }
    }
}