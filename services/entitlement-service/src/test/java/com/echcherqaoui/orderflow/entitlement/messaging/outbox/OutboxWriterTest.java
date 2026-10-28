package com.echcherqaoui.orderflow.entitlement.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.DigitalStockProvisionedEvent;
import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementGrantedEvent;
import com.echcherqaoui.orderflow.entitlement.util.ItemIds;
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

import java.util.List;
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

    private final String itemName = "prod-" + UUID.randomUUID();
    UUID itemId = ItemIds.fromName(itemName);

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

            outboxWriter.publishDigitalStockProvisionedEvent(itemId, itemName, priceCents, count);

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  eq(itemId.toString()),
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
            assertThat(event.getItemName()).isEqualTo(itemName);
            assertThat(event.getPriceCents()).isEqualTo(priceCents);
            assertThat(event.getCountAdded()).isEqualTo(count);
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(itemId.toString());
            assertThat(event.getMetadata().getCausationId()).isEmpty();
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getId()).isNotNull();
            assertThat(savedEvent.getAggregateType()).isEqualTo(ITEM_EVENTS_AGGREGATE_TYPE);
            assertThat(savedEvent.getAggregateId()).isEqualTo(itemName);
            assertThat(savedEvent.getEventType()).isEqualTo("DigitalStockProvisionedEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
            assertThat(savedEvent.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void publishDigitalStockProvisionedEvent_signatureFails_propagatesException() {
            RuntimeException signatureException = new RuntimeException("HMAC signing error");
            given(signatureService.sign(any(String[].class))).willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.publishDigitalStockProvisionedEvent(itemId, itemName, priceCents, count))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("writeEntitlementGranted()")
    class WriteEntitlementGranted {

        private static final String SAGA_EVENTS_TOPIC = "orderflow.entitlement.events";
        private static final String SAGA_EVENTS_AGGREGATE_TYPE = "entitlement.events";

        @Test
        @DisplayName("successful invocation creates EntitlementGrantedEvent, signs metadata, and saves outbox event")
        void writeEntitlementGranted_success_buildsProtobufSignsAndSavesEvent() {
            UUID orderId = UUID.randomUUID();
            String userId = "user-789";
            List<String> accessKeys = List.of("KEY-ABC-123", "KEY-XYZ-789");
            String triggerEventId = UUID.randomUUID().toString();
            String dummySignature = "hmac-signature-67890";

            given(signatureService.sign(any(String[].class))).willReturn(dummySignature);
            given(serializer.serialize(eq(SAGA_EVENTS_TOPIC), any(Message.class))).willReturn(serializedPayload);

            outboxWriter.writeEntitlementGranted(orderId, userId, accessKeys, triggerEventId);

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  eq(orderId.toString()),
                  secondsCaptor.capture(),
                  eq(userId),
                  eq("KEY-ABC-123"),
                  eq("KEY-XYZ-789")
            );

            String capturedMessageId = messageIdCaptor.getValue();
            long capturedSeconds = Long.parseLong(secondsCaptor.getValue());

            assertThat(capturedMessageId).isNotNull();
            assertThat(UUID.fromString(capturedMessageId)).isNotNull();
            assertThat(capturedSeconds).isGreaterThan(0L);

            then(serializer).should().serialize(eq(SAGA_EVENTS_TOPIC), messageCaptor.capture());
            Message capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage).isInstanceOf(EntitlementGrantedEvent.class);

            EntitlementGrantedEvent event = (EntitlementGrantedEvent) capturedMessage;
            assertThat(event.getOrderId()).isEqualTo(orderId.toString());
            assertThat(event.getUserId()).isEqualTo(userId);
            assertThat(event.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(triggerEventId);
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getId()).isNotNull();
            assertThat(savedEvent.getAggregateType()).isEqualTo(SAGA_EVENTS_AGGREGATE_TYPE);
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("EntitlementGrantedEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
            assertThat(savedEvent.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void writeEntitlementGranted_signatureFails_propagatesException() {
            UUID orderId = UUID.randomUUID();
            String userId = "user-789";
            List<String> accessKeys = List.of("KEY-ABC-123");
            String triggerEventId = UUID.randomUUID().toString();

            RuntimeException signatureException = new RuntimeException("HMAC signing error");
            given(signatureService.sign(any(String[].class))).willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.writeEntitlementGranted(orderId, userId, accessKeys, triggerEventId))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }
    }
}