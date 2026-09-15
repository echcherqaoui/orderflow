package com.echcherqaoui.orderflow.order.messaging.outbox;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ExtendReservationCommand;
import com.echcherqaoui.orderflow.contracts.inventory.commands.v1.ReleaseInventoryCommand;
import com.echcherqaoui.orderflow.contracts.order.v1.OrderCancelledIntegrationEvent;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.CancelPaymentCommand;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    private static final String PAYMENT_TOPIC = "orderflow.payment.commands";
    private static final String INVENTORY_TOPIC = "orderflow.inventory.commands";
    private static final String ORDER_EVENTS_TOPIC = "orderflow.order.events";

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
    private final String userId = "user-456";
    private final String cartId = "cart-789";
    private final String causationMessageId = UUID.randomUUID().toString();
    private final String paymentIntentId = "pi_123456";
    private final String reason = "CUSTOMER_CANCELLED";
    private final long totalPriceCents = 9900L;
    private final String dummySignature = "hmac-signature-12345";
    private final byte[] serializedPayload = new byte[]{0x0, 0x1, 0x2, 0x3};

    @Nested
    @DisplayName("publishChargePaymentCommand()")
    class PublishChargePaymentCommand {

        @Test
        @DisplayName("successful invocation signs command, serializes protobuf message, and saves outbox event")
        void publishChargePaymentCommand_success_buildsProtobufSignsAndSavesEvent() {
            given(signatureService.sign(any(String[].class)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(PAYMENT_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] params = signatureParamsCaptor.getValue();

            assertThat(params).hasSize(5);
            assertThat(UUID.fromString(params[0])).isNotNull(); // generated messageId
            assertThat(params[1]).isEqualTo(orderId.toString());
            assertThat(Long.parseLong(params[2])).isGreaterThan(0L); // occurredAt epoch seconds
            assertThat(params[3]).isEqualTo(userId);
            assertThat(params[4]).isEqualTo(String.valueOf(totalPriceCents));

            then(serializer).should().serialize(eq(PAYMENT_TOPIC), messageCaptor.capture());
            Message capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage).isInstanceOf(ChargePaymentCommand.class);

            ChargePaymentCommand command = (ChargePaymentCommand) capturedMessage;
            assertThat(command.getUserId()).isEqualTo(userId);
            assertThat(command.getOrderId()).isEqualTo(orderId.toString());
            assertThat(command.getTotalPriceCents()).isEqualTo(totalPriceCents);
            assertThat(command.getMetadata().getMessageId()).isEqualTo(params[0]);
            assertThat(command.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(command.getMetadata().getCausationId()).isEmpty();
            assertThat(command.getMetadata().getSignature()).isEqualTo(dummySignature);

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
        @DisplayName("null orderId throws NullPointerException")
        void publishChargePaymentCommand_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(null, userId, totalPriceCents))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("orderId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("null userId throws NullPointerException")
        void publishChargePaymentCommand_nullUserId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, null, totalPriceCents))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("userId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("signature service failure propagates exception and halts serialization and persistence")
        void publishChargePaymentCommand_signatureServiceFails_propagatesExceptionAndAborts() {
            RuntimeException signatureException = new RuntimeException("HMAC signing key error");
            given(signatureService.sign(any(String[].class)))
                  .willThrow(signatureException);

            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents))
                  .isSameAs(signatureException);

            verifyNoInteractions(serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("serializer failure propagates exception and halts outbox persistence")
        void publishChargePaymentCommand_serializerFails_propagatesExceptionAndAbortsSave() {
            RuntimeException serializationException = new RuntimeException("Confluent Schema Registry timeout");
            given(signatureService.sign(any(String[].class)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(PAYMENT_TOPIC), any(Message.class)))
                  .willThrow(serializationException);

            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents))
                  .isSameAs(serializationException);

            then(signatureService).should().sign(any(String[].class));
            then(serializer).should().serialize(eq(PAYMENT_TOPIC), any(Message.class));
            verifyNoInteractions(outboxEventRepository);
        }

        @Test
        @DisplayName("repository save failure propagates exception after message construction and serialization")
        void publishChargePaymentCommand_repositorySaveFails_propagatesException() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");
            given(signatureService.sign(any(String[].class)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(PAYMENT_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willThrow(dbException);

            assertThatThrownBy(() -> outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents))
                  .isSameAs(dbException);

            then(signatureService).should().sign(any(String[].class));
            then(serializer).should().serialize(eq(PAYMENT_TOPIC), any(Message.class));
            then(outboxEventRepository).should().save(any(OutboxEvent.class));
        }
    }

    @Nested
    @DisplayName("publishReleaseInventoryCommand()")
    class PublishReleaseInventoryCommand {

        @Test
        @DisplayName("successful invocation signs command with causationId and saves outbox event")
        void publishReleaseInventoryCommand_success_buildsAndSavesEvent() {
            given(signatureService.sign(any(String[].class)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(INVENTORY_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishReleaseInventoryCommand(orderId, causationMessageId, cartId);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] params = signatureParamsCaptor.getValue();

            assertThat(params).hasSize(4);
            assertThat(UUID.fromString(params[0])).isNotNull();
            assertThat(params[1]).isEqualTo(orderId.toString());
            assertThat(Long.parseLong(params[2])).isGreaterThan(0L);
            assertThat(params[3]).isEqualTo(cartId);

            then(serializer).should().serialize(eq(INVENTORY_TOPIC), messageCaptor.capture());
            ReleaseInventoryCommand command = (ReleaseInventoryCommand) messageCaptor.getValue();

            assertThat(command.getCartId()).isEqualTo(cartId);
            assertThat(command.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(command.getMetadata().getCausationId()).isEqualTo(causationMessageId);
            assertThat(command.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getAggregateType()).isEqualTo("inventory.commands");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("ReleaseInventoryCommand");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
        }

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void publishReleaseInventoryCommand_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishReleaseInventoryCommand(null, causationMessageId, cartId))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("orderId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("null cartId throws NullPointerException")
        void publishReleaseInventoryCommand_nullCartId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishReleaseInventoryCommand(orderId, causationMessageId, null))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("cartId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("publishExtendReservationCommand()")
    class PublishExtendReservationCommand {

        @Test
        @DisplayName("successful invocation builds command and saves outbox event")
        void publishExtendReservationCommand_success_buildsAndSavesEvent() {
            given(signatureService.sign(any(String[].class)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(INVENTORY_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishExtendReservationCommand(orderId, causationMessageId, cartId);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] params = signatureParamsCaptor.getValue();

            assertThat(params).hasSize(4);
            assertThat(params[1]).isEqualTo(orderId.toString());
            assertThat(params[3]).isEqualTo(cartId);

            then(serializer).should().serialize(eq(INVENTORY_TOPIC), messageCaptor.capture());
            ExtendReservationCommand command = (ExtendReservationCommand) messageCaptor.getValue();

            assertThat(command.getCartId()).isEqualTo(cartId);
            assertThat(command.getOrderId()).isEqualTo(orderId.toString());
            assertThat(command.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(command.getMetadata().getCausationId()).isEqualTo(causationMessageId);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getAggregateType()).isEqualTo("inventory.commands");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("ExtendReservationCommand");
        }

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void publishExtendReservationCommand_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishExtendReservationCommand(null, causationMessageId, cartId))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("orderId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("null cartId throws NullPointerException")
        void publishExtendReservationCommand_nullCartId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishExtendReservationCommand(orderId, causationMessageId, null))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("cartId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("publishCancelPaymentCommand()")
    class PublishCancelPaymentCommand {

        @Test
        @DisplayName("successful invocation signs command with payment intent and reason and saves outbox event")
        void publishCancelPaymentCommand_success_buildsAndSavesEvent() {
            given(signatureService.sign(any(String[].class)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(PAYMENT_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishCancelPaymentCommand(orderId, causationMessageId, paymentIntentId, reason);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] params = signatureParamsCaptor.getValue();

            assertThat(params).hasSize(5);
            assertThat(params[1]).isEqualTo(orderId.toString());
            assertThat(params[3]).isEqualTo(paymentIntentId);
            assertThat(params[4]).isEqualTo(reason);

            then(serializer).should().serialize(eq(PAYMENT_TOPIC), messageCaptor.capture());
            CancelPaymentCommand command = (CancelPaymentCommand) messageCaptor.getValue();

            assertThat(command.getOrderId()).isEqualTo(orderId.toString());
            assertThat(command.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(command.getReason()).isEqualTo(reason);
            assertThat(command.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(command.getMetadata().getCausationId()).isEqualTo(causationMessageId);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getAggregateType()).isEqualTo("payment.commands");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("CancelPaymentCommand");
        }

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void publishCancelPaymentCommand_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishCancelPaymentCommand(null, causationMessageId, paymentIntentId, reason))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("orderId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("null paymentIntentId throws NullPointerException")
        void publishCancelPaymentCommand_nullPaymentIntentId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishCancelPaymentCommand(orderId, causationMessageId, null, reason))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("paymentIntentId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("null reason throws NullPointerException")
        void publishCancelPaymentCommand_nullReason_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishCancelPaymentCommand(orderId, causationMessageId, paymentIntentId, null))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("reason is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("publishOrderCancelledEvent()")
    class PublishOrderCancelledEvent {

        @Test
        @DisplayName("successful invocation signs integration event with triggerEventId and reason and saves outbox event")
        void publishOrderCancelledEvent_success_buildsAndSavesEvent() {
            given(signatureService.sign(any(String[].class)))
                  .willReturn(dummySignature);
            given(serializer.serialize(eq(ORDER_EVENTS_TOPIC), any(Message.class)))
                  .willReturn(serializedPayload);
            given(outboxEventRepository.save(any(OutboxEvent.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            outboxWriter.publishOrderCancelledEvent(orderId, causationMessageId, reason);

            then(signatureService).should().sign(signatureParamsCaptor.capture());
            String[] params = signatureParamsCaptor.getValue();

            assertThat(params).hasSize(4);
            assertThat(UUID.fromString(params[0])).isNotNull();
            assertThat(params[1]).isEqualTo(orderId.toString());
            assertThat(Long.parseLong(params[2])).isGreaterThan(0L);
            assertThat(params[3]).isEqualTo(reason);

            then(serializer).should().serialize(eq(ORDER_EVENTS_TOPIC), messageCaptor.capture());
            OrderCancelledIntegrationEvent event = (OrderCancelledIntegrationEvent) messageCaptor.getValue();

            assertThat(event.getOrderId()).isEqualTo(orderId.toString());
            assertThat(event.getReason()).isEqualTo(reason);
            assertThat(event.getMetadata().getCorrelationId()).isEqualTo(orderId.toString());
            assertThat(event.getMetadata().getCausationId()).isEqualTo(causationMessageId);
            assertThat(event.getMetadata().getSignature()).isEqualTo(dummySignature);

            then(outboxEventRepository).should().save(outboxEventCaptor.capture());
            OutboxEvent savedEvent = outboxEventCaptor.getValue();

            assertThat(savedEvent.getAggregateType()).isEqualTo("order.events");
            assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(savedEvent.getEventType()).isEqualTo("OrderCancelledIntegrationEvent");
            assertThat(savedEvent.getPayload()).isEqualTo(serializedPayload);
        }

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void publishOrderCancelledEvent_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishOrderCancelledEvent(null, causationMessageId, reason))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("orderId is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }

        @Test
        @DisplayName("null reason throws NullPointerException")
        void publishOrderCancelledEvent_nullReason_throwsNullPointerException() {
            assertThatThrownBy(() -> outboxWriter.publishOrderCancelledEvent(orderId, causationMessageId, null))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("reason is marked non-null but is null");

            verifyNoInteractions(signatureService, serializer, outboxEventRepository);
        }
    }
}