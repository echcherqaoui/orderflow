package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.events.InventoryConfirmationFailedOrderEvent;
import com.echcherqaoui.orderflow.order.events.InventoryConfirmedOrderEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentFailedEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentSessionActiveEvent;
import com.echcherqaoui.orderflow.order.events.PaymentRefundFailedOrderEvent;
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

    // --- Reusable Helper Methods ---

    private Order createOrder(SagaStep step, OrderStatus status) {
        return new Order()
              .setId(orderId)
              .setUserId(userId)
              .setCartId(cartId)
              .setStatus(status)
              .setCurrentSagaStep(step)
              .setTotalAmountCents(totalPriceCents);
    }

    private void verifyTransition(SagaStep closedStep,
                                  SagaStepStatus closedStatus,
                                  SagaStep openedStep,
                                  SagaStepStatus openedStatus,
                                  String eventType,
                                  String eventId) {
        then(sagaStepLogger).should().logTransition(
              eq(orderId),
              argThat(c -> c != null && c.step() == closedStep && c.status() == closedStatus),
              argThat(o -> o != null && o.step() == openedStep && (openedStatus == null || o.status() == openedStatus)),
              eventType != null ? eq(eventType) : any(),
              eventId != null ? eq(eventId) : any()
        );
    }

    private void verifyStaleEventIgnored(Order order, Runnable action) {
        SagaStep initialStep = order.getCurrentSagaStep();
        OrderStatus initialStatus = order.getStatus();
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

        action.run();

        assertThat(order.getCurrentSagaStep()).isEqualTo(initialStep);
        assertThat(order.getStatus()).isEqualTo(initialStatus);
        then(orderRepository).should(never()).save(any());
        then(sagaStepLogger).should(never()).logTransition(any(), any(), any(), any(), any());
        verifyNoInteractions(outboxWriter, eventPublisher);
    }

    private void verifyOrderNotFound(java.util.function.Consumer<UUID> action) {
        given(orderRepository.findById(orderId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> action.accept(orderId))
              .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(sagaStepLogger, outboxWriter, eventPublisher);
    }

    // --- Test Suites ---

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

            verifyTransition(SagaStep.INVENTORY_RESERVED, SagaStepStatus.COMPLETED, SagaStep.INITIALIZING_PAYMENT, SagaStepStatus.STARTED, null, null);
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

            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publishing failure propagates exception after order save and saga step log")
        void handleOrderReserved_outboxWriterFails_propagatesException() {
            RuntimeException outboxException = new RuntimeException("Protobuf serialization error");
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            willThrow(outboxException).given(outboxWriter).publishChargePaymentCommand(orderId, userId, totalPriceCents);

            assertThatThrownBy(() -> orderSagaService.handleOrderReserved(orderId, request, reservation))
                  .isSameAs(outboxException);

            then(outboxWriter).should().publishChargePaymentCommand(orderId, userId, totalPriceCents);
        }
    }

    @Nested
    @DisplayName("handlePaymentInitiated()")
    class HandlePaymentInitiated {

        @Test
        @DisplayName("valid step INITIALIZING_PAYMENT: updates paymentIntentId, sets step EXTENDING_INVENTORY, publishes command and application event")
        void handlePaymentInitiated_validStep_updatesStateAndPublishesOutboxAndEvent() {
            Order existingOrder = createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            String paymentIntentId = "pi_99887766";
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentInitiated(orderId, paymentIntentId, clientSecret, triggerEventId);

            assertThat(existingOrder.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.EXTENDING_INVENTORY);

            verifyTransition(SagaStep.INITIALIZING_PAYMENT, SagaStepStatus.COMPLETED, SagaStep.EXTENDING_INVENTORY, SagaStepStatus.STARTED, null, triggerEventId);
            then(outboxWriter).should().publishExtendReservationCommand(eq(orderId), anyString(), eq(cartId));
            then(eventPublisher).should().publishEvent(any(OrderPaymentSessionActiveEvent.class));
        }

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handlePaymentInitiated_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING),
                  () -> orderSagaService.handlePaymentInitiated(orderId, "pi_stale", clientSecret, triggerEventId));
        }

        @Test
        @DisplayName("missing order: throws ResourceNotFoundException and performs no side effects")
        void handlePaymentInitiated_notFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handlePaymentInitiated(id, "pi_123", clientSecret, triggerEventId));
        }
    }

    @Nested
    @DisplayName("handlePaymentInitializationFailed()")
    class HandlePaymentInitializationFailed {

        @Test
        @DisplayName("valid step INITIALIZING_PAYMENT: sets status CANCELLING, step REVERSING_INVENTORY, publishes release command and application event")
        void handlePaymentInitializationFailed_validStep_updatesStatusAndPublishesReleaseAndEvent() {
            Order existingOrder = createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentInitializationFailed(orderId, "CARD_DECLINED", triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(existingOrder.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_FAILED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_INVENTORY);

            verifyTransition(SagaStep.INITIALIZING_PAYMENT, SagaStepStatus.FAILED, SagaStep.REVERSING_INVENTORY, SagaStepStatus.STARTED, null, triggerEventId);
            then(outboxWriter).should().publishReleaseInventoryCommand(eq(orderId), anyString(), eq(cartId));
            then(eventPublisher).should().publishEvent(any(OrderPaymentFailedEvent.class));
        }
    }

    @Nested
    @DisplayName("handleReservationExtended()")
    class HandleReservationExtended {

        @Test
        @DisplayName("valid step EXTENDING_INVENTORY: updates step to PAYMENT_SESSION_ACTIVE and publishes application event")
        void handleReservationExtended_validStep_updatesSagaStepAndPublishesEvent() {
            Order existingOrder = createOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleReservationExtended(orderId, cartId, Instant.now().plusSeconds(900), triggerEventId);

            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.PAYMENT_SESSION_ACTIVE);

            verifyTransition(SagaStep.EXTENDING_INVENTORY, SagaStepStatus.COMPLETED, SagaStep.PAYMENT_SESSION_ACTIVE, null, null, triggerEventId);
            verifyNoInteractions(outboxWriter);
            then(eventPublisher).should().publishEvent(any(ReservationExtendedOrderEvent.class));
        }
    }

    @Nested
    @DisplayName("handleReservationExtensionFailed()")
    class HandleReservationExtensionFailed {

        @Test
        @DisplayName("valid step EXTENDING_INVENTORY: sets status CANCELLING, step REVERSING_PAYMENT, publishes cancel payment command and application event")
        void handleReservationExtensionFailed_validStep_updatesStatusAndPublishesCancelPaymentAndEvent() {
            Order existingOrder = createOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            existingOrder.setPaymentIntentId("pi_12345");
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleReservationExtensionFailed(orderId, UUID.randomUUID().toString(), cartId, "LOCK_TIMEOUT", triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);

            verifyTransition(SagaStep.EXTENDING_INVENTORY, SagaStepStatus.FAILED, SagaStep.REVERSING_PAYMENT, SagaStepStatus.STARTED, null, triggerEventId);
            then(outboxWriter).should().publishCancelPaymentCommand(eq(orderId), anyString(), eq("pi_12345"), anyString());
            then(eventPublisher).should().publishEvent(any(ReservationExtensionFailedOrderEvent.class));
        }
    }

    @Nested
    @DisplayName("handlePaymentFailed()")
    class HandlePaymentFailed {

        @Test
        @DisplayName("valid step PAYMENT_SESSION_ACTIVE: updates order status to CANCELLING, step to REVERSING_INVENTORY, and publishes release command + event")
        void handlePaymentFailed_validStep_initiatesInventoryCompensation() {
            Order order = createOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

            String reason = "INSUFFICIENT_FUNDS";
            orderSagaService.handlePaymentFailed(orderId, reason, triggerEventId);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(order.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_INVENTORY);

            verifyTransition(SagaStep.PAYMENT_SESSION_ACTIVE, SagaStepStatus.FAILED, SagaStep.REVERSING_INVENTORY, SagaStepStatus.STARTED, "PaymentFailedEvent", triggerEventId);
            then(outboxWriter).should().publishReleaseInventoryCommand(orderId, triggerEventId, cartId);
            then(eventPublisher).should().publishEvent(new OrderPaymentFailedEvent(orderId, reason));
        }

        @Test
        @DisplayName("stale or duplicate step: ignores event and performs no state updates or side effects")
        void handlePaymentFailed_staleStep_ignoresEventAndAborts() {
            verifyStaleEventIgnored(createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING),
                  () -> orderSagaService.handlePaymentFailed(orderId, "CARD_DECLINED", triggerEventId));
        }

        @Test
        @DisplayName("missing order: throws ResourceNotFoundException and performs no side effects")
        void handlePaymentFailed_orderNotFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handlePaymentFailed(id, "CARD_DECLINED", triggerEventId));
        }
    }

    @Nested
    @DisplayName("handlePaymentCharged()")
    class HandlePaymentCharged {

        @Test
        @DisplayName("valid step PAYMENT_SESSION_ACTIVE: updates step to CONFIRMING_INVENTORY, logs saga transition, and publishes confirm command")
        void handlePaymentCharged_validStep_updatesSagaStepAndPublishesConfirmCommand() {
            Order existingOrder = createOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentCharged(orderId, "pi_99887766", triggerEventId);

            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.CONFIRMING_INVENTORY);

            verifyTransition(SagaStep.PAYMENT_SESSION_ACTIVE, SagaStepStatus.COMPLETED, SagaStep.CONFIRMING_INVENTORY, SagaStepStatus.STARTED, "PaymentChargedEvent", triggerEventId);
            then(outboxWriter).should().publishConfirmReservationCommand(orderId, triggerEventId, cartId);
        }

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handlePaymentCharged_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING),
                  () -> orderSagaService.handlePaymentCharged(orderId, "pi_stale", triggerEventId));
        }

        @Test
        @DisplayName("missing order: throws ResourceNotFoundException and performs no side effects")
        void handlePaymentCharged_notFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handlePaymentCharged(id, "pi_123", triggerEventId));
        }
    }

    @Nested
    @DisplayName("handleInventoryReleased()")
    class HandleInventoryReleased {

        @Test
        @DisplayName("valid step REVERSING_INVENTORY: updates status CANCELLED, step REVERSED, and publishes terminal outbox event")
        void handleInventoryReleased_validStep_completesCancellationAndPublishesOutbox() {
            Order existingOrder = createOrder(SagaStep.REVERSING_INVENTORY, OrderStatus.CANCELLING);
            existingOrder.setCancellationReason(CancellationReason.PAYMENT_FAILED);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleInventoryReleased(orderId, cartId, triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);

            verifyTransition(SagaStep.REVERSING_INVENTORY, SagaStepStatus.COMPLETED, SagaStep.REVERSED, null, null, triggerEventId);
            then(outboxWriter).should().publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.PAYMENT_FAILED.name());
        }
    }

    @Nested
    @DisplayName("handlePaymentCancelled()")
    class HandlePaymentCancelled {

        @Test
        @DisplayName("valid step REVERSING_PAYMENT: updates status CANCELLED, step REVERSED, and publishes terminal outbox event")
        void handlePaymentCancelled_validStep_completesCancellationAndPublishesOutbox() {
            Order existingOrder = createOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            existingOrder.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentCancelled(orderId, "pi_12345", triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);

            verifyTransition(SagaStep.REVERSING_PAYMENT, SagaStepStatus.COMPLETED, SagaStep.REVERSED, null, null, triggerEventId);
            then(outboxWriter).should().publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.RESERVATION_EXPIRED.name());
        }

        @Test
        @DisplayName("stale PaymentCancelledEvent for unexpected step is ignored")
        void handlePaymentCancelled_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING),
                  () -> orderSagaService.handlePaymentCancelled(orderId, "pi_stale", triggerEventId));
        }
    }

    @Nested
    @DisplayName("handleInventoryConfirmationFailed()")
    class HandleInventoryConfirmationFailed {

        @Test
        @DisplayName("valid step CONFIRMING_INVENTORY: transitions status to CANCELLING, step to REVERSING_PAYMENT, logs saga transition, and publishes refund command and local domain event")
        void handleInventoryConfirmationFailed_validStep_initiatesPaymentRefundCompensation() {
            Order existingOrder = createOrder(SagaStep.CONFIRMING_INVENTORY, OrderStatus.PENDING);
            existingOrder.setPaymentIntentId("pi_123456789");
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            String reason = "OUT_OF_STOCK";
            orderSagaService.handleInventoryConfirmationFailed(orderId, reason, triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);

            verifyTransition(SagaStep.CONFIRMING_INVENTORY, SagaStepStatus.FAILED, SagaStep.REVERSING_PAYMENT, SagaStepStatus.STARTED, "InventoryConfirmationFailedEvent", triggerEventId);
            then(outboxWriter).should().publishRefundPaymentCommand(orderId, triggerEventId, "pi_123456789", reason);
            then(eventPublisher).should().publishEvent(new InventoryConfirmationFailedOrderEvent(orderId, reason));
        }

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handleInventoryConfirmationFailed_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING),
                  () -> orderSagaService.handleInventoryConfirmationFailed(orderId, "OUT_OF_STOCK", triggerEventId));
        }

        @Test
        @DisplayName("missing order: throws ResourceNotFoundException and performs no side effects")
        void handleInventoryConfirmationFailed_notFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handleInventoryConfirmationFailed(id, "OUT_OF_STOCK", triggerEventId));
        }
    }
    @Nested
    @DisplayName("handlePaymentRefunded()")
    class HandlePaymentRefunded {

        @Test
        @DisplayName("valid step REVERSING_PAYMENT: sets status CANCELLED, step REFUNDED, logs transition, publishes outbox and application events")
        void handlePaymentRefunded_validStep_completesSagaAndPublishesEvents() {
            Order existingOrder = createOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            existingOrder.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handlePaymentRefunded(orderId, "pi_refunded_123", triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.REFUNDED);

            then(orderRepository).should().save(existingOrder);
            verifyTransition(SagaStep.REVERSING_PAYMENT, SagaStepStatus.COMPLETED, SagaStep.REFUNDED, SagaStepStatus.COMPLETED, "PaymentRefundedEvent", triggerEventId);
            then(outboxWriter).should().publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.RESERVATION_EXPIRED.name());
        }

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handlePaymentRefunded_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.REFUNDED, OrderStatus.CANCELLED),
                  () -> orderSagaService.handlePaymentRefunded(orderId, "pi_stale_123", triggerEventId));
        }

        @Test
        @DisplayName("missing order throws ResourceNotFoundException and halts execution")
        void handlePaymentRefunded_notFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handlePaymentRefunded(id, "pi_123", triggerEventId));
        }

        @Test
        @DisplayName("outbox publication failure propagates exception and halts event publishing")
        void handlePaymentRefunded_outboxWriterFails_propagatesException() {
            Order existingOrder = createOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            existingOrder.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            RuntimeException outboxException = new RuntimeException("Kafka Outbox persistence failed");
            willThrow(outboxException)
                  .given(outboxWriter)
                  .publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.RESERVATION_EXPIRED.name());

            assertThatThrownBy(() -> orderSagaService.handlePaymentRefunded(orderId, "pi_refunded_123", triggerEventId))
                  .isSameAs(outboxException);

            then(orderRepository).should().save(existingOrder);
            verifyTransition(SagaStep.REVERSING_PAYMENT, SagaStepStatus.COMPLETED, SagaStep.REFUNDED, SagaStepStatus.COMPLETED, "PaymentRefundedEvent", triggerEventId);
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("handlePaymentRefundFailed()")
    class HandlePaymentRefundFailed {

        @Test
        @DisplayName("valid step REVERSING_PAYMENT: sets status CANCELLED, reason REFUND_FAILED, step MANUAL_INTERVENTION_REQUIRED, publishes outbox and application events")
        void handlePaymentRefundFailed_validStep_transitionsToManualInterventionAndPublishesEvents() {
            Order existingOrder = createOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            existingOrder.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            String failureReason = "GATEWAY_TIMEOUT";
            orderSagaService.handlePaymentRefundFailed(orderId, "pi_refund_fail_123", failureReason, triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(existingOrder.getCancellationReason()).isEqualTo(CancellationReason.REFUND_FAILED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.MANUAL_INTERVENTION_REQUIRED);

            then(orderRepository).should().save(existingOrder);
            verifyTransition(SagaStep.REVERSING_PAYMENT, SagaStepStatus.FAILED, SagaStep.MANUAL_INTERVENTION_REQUIRED, SagaStepStatus.COMPLETED, "PaymentRefundFailedEvent", triggerEventId);
            then(outboxWriter).should().publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.REFUND_FAILED.name());
        }

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handlePaymentRefundFailed_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.MANUAL_INTERVENTION_REQUIRED, OrderStatus.CANCELLED),
                  () -> orderSagaService.handlePaymentRefundFailed(orderId, "pi_stale_123", "GATEWAY_TIMEOUT", triggerEventId));
        }

        @Test
        @DisplayName("missing order throws ResourceNotFoundException and halts execution")
        void handlePaymentRefundFailed_notFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handlePaymentRefundFailed(id, "pi_123", "GATEWAY_TIMEOUT", triggerEventId));
        }

        @Test
        @DisplayName("event publisher failure propagates exception after database save, saga step log, and outbox publish")
        void handlePaymentRefundFailed_eventPublisherFails_propagatesException() {
            Order existingOrder = createOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            RuntimeException publisherException = new RuntimeException("ApplicationEventPublisher error");
            willThrow(publisherException)
                  .given(eventPublisher)
                  .publishEvent(any(PaymentRefundFailedOrderEvent.class));

            assertThatThrownBy(() -> orderSagaService.handlePaymentRefundFailed(orderId, "pi_123", "GATEWAY_TIMEOUT", triggerEventId))
                  .isSameAs(publisherException);

            then(orderRepository).should().save(existingOrder);
            verifyTransition(SagaStep.REVERSING_PAYMENT, SagaStepStatus.FAILED, SagaStep.MANUAL_INTERVENTION_REQUIRED, SagaStepStatus.COMPLETED, "PaymentRefundFailedEvent", triggerEventId);
            then(outboxWriter).should().publishOrderCancelledEvent(orderId, triggerEventId, CancellationReason.REFUND_FAILED.name());
        }
    }

    @Nested
    @DisplayName("handleInventoryConfirmed()")
    class HandleInventoryConfirmed {

        @Test
        @DisplayName("valid step CONFIRMING_INVENTORY: updates step to GRANTING_ENTITLEMENT, logs saga transition, publishes grant entitlement command, and publishes application event")
        void handleInventoryConfirmed_validStep_updatesSagaStepAndPublishesCommandAndEvent() {
            Order existingOrder = createOrder(SagaStep.CONFIRMING_INVENTORY, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleInventoryConfirmed(orderId, cartId, triggerEventId);

            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.GRANTING_ENTITLEMENT);

            then(orderRepository).should().save(existingOrder);
            verifyTransition(SagaStep.CONFIRMING_INVENTORY, SagaStepStatus.COMPLETED, SagaStep.GRANTING_ENTITLEMENT, SagaStepStatus.STARTED, "InventoryConfirmedEvent", triggerEventId);
            then(outboxWriter).should().publishGrantEntitlementCommand(
                  orderId,
                  triggerEventId,
                  userId,
                  existingOrder.getItems()
            );
            then(eventPublisher).should().publishEvent(new InventoryConfirmedOrderEvent(orderId));
        }

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handleInventoryConfirmed_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING),
                  () -> orderSagaService.handleInventoryConfirmed(orderId, cartId, triggerEventId));
        }

        @Test
        @DisplayName("missing order: throws ResourceNotFoundException and performs no side effects")
        void handleInventoryConfirmed_notFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handleInventoryConfirmed(id, cartId, triggerEventId));
        }
    }

    @Nested
    @DisplayName("handleEntitlementGranted()")
    class HandleEntitlementGranted {

        @Test
        @DisplayName("valid step GRANTING_ENTITLEMENT: sets status COMPLETED, step COMPLETED, logs transition, and publishes outbox and application events")
        void handleEntitlementGranted_validStep_completesOrderAndPublishesEvents() {
            Order existingOrder = createOrder(SagaStep.GRANTING_ENTITLEMENT, OrderStatus.PENDING);
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));

            orderSagaService.handleEntitlementGranted(orderId, triggerEventId);

            assertThat(existingOrder.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(existingOrder.getCurrentSagaStep()).isEqualTo(SagaStep.COMPLETED);

            then(orderRepository).should().save(existingOrder);
            verifyTransition(SagaStep.GRANTING_ENTITLEMENT, SagaStepStatus.COMPLETED, SagaStep.COMPLETED, SagaStepStatus.COMPLETED, "EntitlementGrantedEvent", triggerEventId);
            then(outboxWriter).should().publishOrderCompletedEvent(orderId, triggerEventId);
            then(eventPublisher).should().publishEvent(any(com.echcherqaoui.orderflow.order.events.OrderConfirmedEvent.class));
        }

        @Test
        @DisplayName("stale event for unexpected step is ignored and produces no side effects")
        void handleEntitlementGranted_staleStep_ignoresEvent() {
            verifyStaleEventIgnored(createOrder(SagaStep.COMPLETED, OrderStatus.COMPLETED),
                  () -> orderSagaService.handleEntitlementGranted(orderId, triggerEventId));
        }

        @Test
        @DisplayName("missing order throws ResourceNotFoundException and halts execution")
        void handleEntitlementGranted_notFound_throwsResourceNotFoundException() {
            verifyOrderNotFound(id -> orderSagaService.handleEntitlementGranted(id, triggerEventId));
        }
    }
}