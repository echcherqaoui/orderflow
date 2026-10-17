package com.echcherqaoui.orderflow.entitlement.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.security.service.SignatureService;
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
    private static final String ITEM_EVENTS_TOPIC = "orderflow.entitlement.item-events";
    private static final String ITEM_EVENTS_AGGREGATE_TYPE = "entitlement.item-events";

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

    private final String productId = "prod-" + UUID.randomUUID();
    private final Long priceCents = 1999L;
    private final Integer count = 5;

    private final byte[] serializedPayload = new byte[]{0x1, 0x2, 0x3, 0x4};

    @Nested
    @DisplayName("publishDigitalStockProvisionedEvent()")
    class PublishDigitalStockProvisionedEvent {

        @Test
        @DisplayName("successful invocation creates DigitalStockProvisionedEvent, signs metadata, and saves outbox event")
        void publishDigitalStockProvisionedEvent_success_buildsProtobufSignsAndSavesEvent() {
            String dummySignature = "hmac-signature-12345";
            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(ITEM_EVENTS_TOPIC), any(Message.class))).willReturn(serializedPayload);

            outboxWriter.publishDigitalStockProvisionedEvent(productId, priceCents, count);

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  eq(productId),
                  secondsCaptor.capture(),
                  eq(priceCents.toString()),
                  eq(count.toString())
            );

            String capturedMessageId = messageIdCaptor.getValue();
            long capturedSeconds = Long.parseLong(secondsCaptor.getValue());

            assertThat(capturedMessageId).isNotNull();
            assertThat(UUID.fromString(capturedMessageId)).isNotNull();
            assertThat(capturedSeconds).isGreaterThan(0L);

            then(serializer).should().serialize(eq(ITEM_EVENTS_TOPIC), messageCaptor.capture());
            Message capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage).isInstanceOf(DigitalStockProvisionedEvent.class);

            DigitalStockProvisionedEvent event = (DigitalStockProvisionedEvent) capturedMessage;
            assertThat(event.getProductId()).isEqualTo(productId);
            assertThat(event.getPriceCents()).isEqualTo(priceCents);
            assertThat(event.getCountAdded()).isEqualTo(count);
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(productId);
            assertThat(event.getMetadata().getCausationId()).isEmpty();
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getId()).isNotNull();
            assertThat(savedEvent.getAggregateType()).isEqualTo(ITEM_EVENTS_AGGREGATE_TYPE);
            assertThat(savedEvent.getAggregateId()).isEqualTo(productId);
            assertThat(savedEvent.getEventType()).isEqualTo("DigitalStockProvisionedEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
            assertThat(savedEvent.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void publishDigitalStockProvisionedEvent_signatureFails_propagatesException() {
            RuntimeException signatureException = new RuntimeException("HMAC signing error");
            given(signatureService.sign(any(String[].class))).willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.publishDigitalStockProvisionedEvent(productId, priceCents, count))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }
    }
}