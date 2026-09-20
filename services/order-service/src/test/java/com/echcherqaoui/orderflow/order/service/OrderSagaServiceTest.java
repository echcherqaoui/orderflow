package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.events.OrderPaymentFailedEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentSessionActiveEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtendedOrderEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtensionFailedOrderEvent;
import com.echcherqaoui.orderflow.order.exception.domain.ResourceNotFoundException;
import com.echcherqaoui.orderflow.order.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.order.model.Order;
import com.echcherqaoui.orderflow.order.model.OrderItem;
import com.echcherqaoui.orderflow.order.model.enums.CancellationReason;
import com.echcherqaoui.orderflow.order.model.enums.OrderStatus;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderSagaServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private SagaStepLogger sagaStepLogger;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderSagaService orderSagaService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    private final UUID orderId = UUID.randomUUID();
    private final String cartId = UUID.randomUUID().toString();
    private final String userId = "user-789";
    private final String item1IdStr = UUID.randomUUID().toString();
    private final String item2IdStr = UUID.randomUUID().toString();
    private final List<String> reservedItemIds = List.of(item1IdStr, item2IdStr);
    private final long totalPriceCents = 12500L;
    private final String triggerEventId = UUID.randomUUID().toString();
    private final String clientSecret = "secret_12345";

    private CreateOrderRequest request;
    private InventoryServiceClient.ReservationResult reservation;

    @BeforeEach
    void setUp() {
        request = new CreateOrderRequest(cartId, userId, reservedItemIds);
        reservation = new InventoryServiceClient.ReservationResult(reservedItemIds, totalPriceCents);
    }

    @Nested
    @DisplayName("Order Lookup")
    class OrderLookup {

        @Test
        @DisplayName("throws ResourceNotFoundException when target order does not exist")
        void getOrder_notFound_throwsResourceNotFoundException() {
            given(orderRepository.findById(orderId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> orderSagaService.handlePaymentInitiated(orderId, "pi_123", clientSecret, triggerEventId))
                  .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(sagaStepLogger, outboxWriter, eventPublisher);
        }
    }

    @Nested
    @DisplayName("handleOrderReserved()")
    class HandleOrderReserved {

        @Test
        @DisplayName("successful invocation maps entity, sets step INITIALIZING_PAYMENT, logs saga transition, and publishes outbox command")
        void handleOrderReserved_success_persistsOrderLogsSagaAndPublishesOutbox() {
            given(orderRepository.save(any(Order.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            orderSagaService.handleOrderReserved(orderId, request, reservation);

            then(orderRepository).should().save(orderCaptor.capture());
            Order savedOrder = orderCaptor.getValue();

            assertThat(savedOrder).isNotNull();
            assertThat(savedOrder.getId()).isEqualTo(orderId);
            assertThat(savedOrder.getUserId()).isEqualTo(userId);
            assertThat(savedOrder.getCartId()).isEqualTo(cartId);
            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(savedOrder.getCurrentSagaStep()).isEqualTo(SagaStep.INITIALIZING_PAYMENT);
            assertThat(savedOrder.getTotalAmountCents()).isEqualTo(totalPriceCents);
            assertThat(savedOrder.getItems())
                  .hasSize(2)
                  .extracting(OrderItem::getItemId)
                  .containsExactlyInAnyOrder(UUID.fromString(item1IdStr), UUID.fromString(item2IdStr));

            then(sagaStepLogger).should().logTransition(
                  eq(orderId),
                  argThat(closed -> closed != null && closed.step() == SagaStep.INVENTORY_RESERVED && closed.status() == SagaStepStatus.COMPLETED),
                  argThat(opened -> opened != null && opened.step() == SagaStep.INITIALIZING_PAYMENT && opened.status() == SagaStepStatus.STARTED),
                  any(),
                  any()
            );

            then(outboxWriter).should().publishChargePaymentCommand(orderId, userId, totalPriceCents);
        }

        @Test
        @DisplayName("repository save failure propagates exception and halts saga logging and outbox publishing")
        void handleOrderReserved_repositorySaveFails_propagatesExceptionAndAborts() {
            RuntimeException dbException = new RuntimeException("Database connection failure");
            given(orderRepository.save(any(Order.class))).willThrow(dbException);

            assertThatThrownBy(() -> orderSagaService.handleOrderReserved(orderId, request, reservation))
                  .isSameAs(dbException);

            then(orderRepository).should().save(any(Order.class));
            verifyNoInteractions(sagaStepLogger, outboxWriter);
        }

        @Test
        @DisplayName("saga step logging failure propagates exception and aborts outbox publishing")
        void handleOrderReserved_sagaStepLoggerFails_propagatesExceptionAndAbortsOutbox() {
            RuntimeException logException = new RuntimeException("Audit log failure");
            given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));
            willThrow(logException)
                  .given(sagaStepLogger)
                  .logTransition(any(), any(), any(), any(), any());

            assertThatThrownBy(() -> orderSagaService.handleOrderReserved(orderId, request, reservation))
                  .isSameAs(logException);

            then(orderRepository).should().save(any(Order.class));
            then(sagaStepLogger).should().logTransition(any(), any(), any(), any(), any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publishing failure propagates exception after order save and saga step log")
        void handleOrderReserved_outboxWriterFails_propagatesException() {
            RuntimeException outboxException = new RuntimeException("Protobuf serialization error");
            given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .publishChargePaymentCommand(orderId, userId, totalPriceCents);

            assertThatThrownBy(() -> orderSagaService.handleOrderReserved(orderId, request, reservation))
                  .isSameAs(outboxException);

            then(orderRepository).should().save(any(Order.class));
            then(sagaStepLogger).should().logTransition(any(), any(), any(), any(), any());
            then(outboxWriter).should().publishChargePaymentCommand(orderId, userId, totalPriceCents);
        }
    }

    @Nested
    @DisplayName("Saga Step Transitions")
    class SagaStepTransitions {

        @Test
        @DisplayName("handlePaymentInitiated updates paymentIntentId, sets step EXTENDING_INVENTORY, publishes command and application event")
        void handlePaymentInitiated_validStep_updatesStateAndPublishesOutboxAndEvent() {
            Order existingOrder = createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            String paymentIntentId = "pi_99887766";

            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentInitiated(orderId, paymentIntentId, clientSecret, triggerEventId);

            assertThat(existingOrder.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.EXTENDING_INVENTORY);

            then(sagaStepLogger).should().logTransition(
                  eq(orderId),
                  argThat(closed -> closed != null && closed.step() == SagaStep.INITIALIZING_PAYMENT && closed.status() == SagaStepStatus.COMPLETED),
                  argThat(opened -> opened != null && opened.step() == SagaStep.EXTENDING_INVENTORY && opened.status() == SagaStepStatus.STARTED),
                  any(),
                  eq(triggerEventId)
            );
            then(outboxWriter).should().publishExtendReservationCommand(eq(orderId), anyString(), eq(cartId));
            then(eventPublisher).should().publishEvent(any(OrderPaymentSessionActiveEvent.class));
        }

        @Test
        @DisplayName("handlePaymentInitializationFailed sets status CANCELLING, step REVERSING_INVENTORY, publishes release command and application event")
        void handlePaymentInitializationFailed_validStep_updatesStatusAndPublishesReleaseAndEvent() {
            Order existingOrder = createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentInitializationFailed(orderId, "CARD_DECLINED", triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(existingOrder.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_FAILED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_INVENTORY);

            then(sagaStepLogger).should().logTransition(
                  eq(orderId),
                  argThat(closed -> closed != null && closed.step() == SagaStep.INITIALIZING_PAYMENT && closed.status() == SagaStepStatus.FAILED),
                  argThat(opened -> opened != null && opened.step() == SagaStep.REVERSING_INVENTORY && opened.status() == SagaStepStatus.STARTED),
                  any(),
                  eq(triggerEventId)
            );
            then(outboxWriter).should().publishReleaseInventoryCommand(eq(orderId), anyString(), eq(cartId));
            then(eventPublisher).should().publishEvent(any(OrderPaymentFailedEvent.class));
        }

        @Test
        @DisplayName("handleReservationExtended updates step to PAYMENT_SESSION_ACTIVE and publishes application event")
        void handleReservationExtended_validStep_updatesSagaStepAndPublishesEvent() {
            Order existingOrder = createOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleReservationExtended(orderId, cartId, Instant.now().plusSeconds(900), triggerEventId);

            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.PAYMENT_SESSION_ACTIVE);

            then(sagaStepLogger).should().logTransition(
                  eq(orderId),
                  argThat(closed -> closed != null && closed.step() == SagaStep.EXTENDING_INVENTORY && closed.status() == SagaStepStatus.COMPLETED),
                  argThat(opened -> opened != null && opened.step() == SagaStep.PAYMENT_SESSION_ACTIVE),
                  any(),
                  eq(triggerEventId)
            );
            verifyNoInteractions(outboxWriter);
            then(eventPublisher).should().publishEvent(any(ReservationExtendedOrderEvent.class));
        }

        @Test
        @DisplayName("handleReservationExtensionFailed sets status CANCELLING, step REVERSING_PAYMENT, publishes cancel payment command and application event")
        void handleReservationExtensionFailed_validStep_updatesStatusAndPublishesCancelPaymentAndEvent() {
            Order existingOrder = createOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            existingOrder.setPaymentIntentId("pi_12345");
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleReservationExtensionFailed(
                  orderId,
                  UUID.randomUUID().toString(),
                  cartId,
                  "LOCK_TIMEOUT",
                  triggerEventId
            );

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(existingOrder.getCancellationReason()).isEqualTo(CancellationReason.RESERVATION_EXPIRED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);

            then(sagaStepLogger).should().logTransition(
                  eq(orderId),
                  argThat(closed -> closed != null && closed.step() == SagaStep.EXTENDING_INVENTORY && closed.status() == SagaStepStatus.FAILED),
                  argThat(opened -> opened != null && opened.step() == SagaStep.REVERSING_PAYMENT && opened.status() == SagaStepStatus.STARTED),
                  any(),
                  eq(triggerEventId)
            );
            then(outboxWriter).should().publishCancelPaymentCommand(eq(orderId), anyString(), eq("pi_12345"), anyString());
            then(eventPublisher).should().publishEvent(any(ReservationExtensionFailedOrderEvent.class));
        }

        @Test
        @DisplayName("handleInventoryReleased updates status CANCELLED, step REVERSED, and publishes terminal outbox event")
        void handleInventoryReleased_validStep_completesCancellationAndPublishesOutbox() {
            Order existingOrder = createOrder(SagaStep.REVERSING_INVENTORY, OrderStatus.CANCELLING);
            existingOrder.setCancellationReason(CancellationReason.PAYMENT_FAILED);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleInventoryReleased(orderId, cartId, triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);

            then(sagaStepLogger).should().logTransition(
                  eq(orderId),
                  argThat(closed -> closed != null && closed.step() == SagaStep.REVERSING_INVENTORY && closed.status() == SagaStepStatus.COMPLETED),
                  argThat(opened -> opened != null && opened.step() == SagaStep.REVERSED),
                  any(),
                  eq(triggerEventId)
            );
            then(outboxWriter).should().publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.PAYMENT_FAILED.name());
        }

        @Test
        @DisplayName("handlePaymentCancelled updates status CANCELLED, step REVERSED, and publishes terminal outbox event")
        void handlePaymentCancelled_validStep_completesCancellationAndPublishesOutbox() {
            Order existingOrder = createOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            existingOrder.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            String paymentIntentId = "pi_12345";
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentCancelled(orderId, paymentIntentId, triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);

            then(sagaStepLogger).should().logTransition(
                  eq(orderId),
                  argThat(closed -> closed != null && closed.step() == SagaStep.REVERSING_PAYMENT && closed.status() == SagaStepStatus.COMPLETED),
                  argThat(opened -> opened != null && opened.step() == SagaStep.REVERSED),
                  any(),
                  eq(triggerEventId)
            );
            then(outboxWriter).should().publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.RESERVATION_EXPIRED.name());
        }
    }

    @Nested
    @DisplayName("Idempotency Guard")
    class IdempotencyGuard {

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handlePaymentInitiated_staleStep_ignoresEvent() {
            Order existingOrder = createOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentInitiated(orderId, "pi_stale", clientSecret, triggerEventId);

            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.PAYMENT_SESSION_ACTIVE);
            then(sagaStepLogger).should(never()).logTransition(any(), any(), any(), any(), any());
            verifyNoInteractions(outboxWriter, eventPublisher);
        }

        @Test
        @DisplayName("stale PaymentCancelledEvent for unexpected step is ignored")
        void handlePaymentCancelled_staleStep_ignoresEvent() {
            Order existingOrder = createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentCancelled(orderId, "pi_stale", triggerEventId);

            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.INITIALIZING_PAYMENT);
            then(sagaStepLogger).should(never()).logTransition(any(), any(), any(), any(), any());
            verifyNoInteractions(outboxWriter, eventPublisher);
        }
    }

    private Order createOrder(SagaStep step, OrderStatus status) {
        return new Order()
              .setId(orderId)
              .setUserId(userId)
              .setCartId(cartId)
              .setStatus(status)
              .setCurrentSagaStep(step)
              .setTotalAmountCents(totalPriceCents);
    }
}