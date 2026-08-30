package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.ChargePaymentCommand;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

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
    private ArgumentCaptor<Long> secondsCaptor;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-456";
    private final long totalPriceCents = 9900L;
    private final String dummySignature = "hmac-signature-12345";
    private final byte[] serializedPayload = new byte[]{0x0, 0x1, 0x2, 0x3};

    @Nested
    @DisplayName("publishChargePaymentCommand()")
    class PublishChargePaymentCommand {
        @Test
        @DisplayName("successful invocation signs command, serializes protobuf message, and saves outbox event")
        void publishChargePaymentCommand_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(), anyLong(), eq(orderId), eq(userId), eq(totalPriceCents)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq("outbox-serialization-context"), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents);

            then(signatureService).should().sign(
                  messageIdCaptor.capture(),
                  secondsCaptor.capture(),
                  eq(orderId),
                  eq(userId),
                  eq(totalPriceCents)
            );
            String capturedMessageId = messageIdCaptor.getValue();
            Long capturedSeconds = secondsCaptor.getValue();

            assertThat(capturedMessageId).isNotNull();
            assertThat(UUID.fromString(capturedMessageId)).isNotNull();
            assertThat(capturedSeconds).isGreaterThan(0L);

            then(serializer).should().serialize(eq("outbox-serialization-context"), messageCaptor.capture());
            Message capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage).isInstanceOf(ChargePaymentCommand.class);

            ChargePaymentCommand command = (ChargePaymentCommand) capturedMessage;
            assertThat(command.getUserId()).isEqualTo(userId);
            assertThat(command.getOrderId()).isEqualTo(orderId.toString());
            assertThat(command.getTotalPriceCents()).isEqualTo(totalPriceCents);
            assertThat(command.getMetadata().getMessageId()).isEqualTo(capturedMessageId);
            assertThat(command.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(command.getMetadata().getSignature()).isEqualTo(dummySignature);
            assertThat(command.getMetadata().getOccurredAt().getSeconds()).isEqualTo(capturedSeconds);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getId()).isNotNull();
            assertThat(savedEvent.getAggregateType()).isEqualTo("payment.commands");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("ChargePaymentCommand");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
            assertThat(savedEvent.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void publishChargePaymentCommand_signatureServiceFails_propagatesExceptionAndAborts() {
            RuntimeException signatureException = new RuntimeException("HMAC signing key error");
            given(signatureService.sign(any(), anyLong(), eq(orderId), eq(userId), eq(totalPriceCents)))
                  .willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("serializer failure propagates exception and halts outbox persistence")
        void publishChargePaymentCommand_serializerFails_propagatesExceptionAndAbortsSave() {
            RuntimeException serializationException = new RuntimeException("Confluent Schema Registry timeout");
            given(signatureService.sign(any(), anyLong(), eq(orderId), eq(userId), eq(totalPriceCents)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq("outbox-serialization-context"), any(Message.class)))
                  .willThrow(serializationException);

            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents))
                  .isSameAs(serializationException);

            then(signatureService).should().sign(any(), anyLong(), eq(orderId), eq(userId), eq(totalPriceCents));
            then(serializer).should().serialize(eq("outbox-serialization-context"), any(Message.class));
            verifyNoInteractions(outboxEventRepository);
        }

        @Test
        @DisplayName("repository save failure propagates exception after message construction and serialization")
        void publishChargePaymentCommand_repositorySaveFails_propagatesException() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");
            given(signatureService.sign(any(), anyLong(), eq(orderId), eq(userId), eq(totalPriceCents)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq("outbox-serialization-context"), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willThrow(dbException);

            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents))
                  .isSameAs(dbException);

            then(signatureService).should().sign(any(), anyLong(), eq(orderId), eq(userId), eq(totalPriceCents));
            then(serializer).should().serialize(eq("outbox-serialization-context"), any(Message.class));
            then(outboxEventRepository).should().save(any(OutboxEvent.class));
        }
    }
}