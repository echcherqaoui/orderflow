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
import com.echcherqaoui.orderflow.order.model.enums.OrderStatus;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.echcherqaoui.orderflow.order.exception.code.OrderErrorCode.ORDER_NOT_FOUND;
import static com.echcherqaoui.orderflow.order.model.enums.CancellationReason.PAYMENT_FAILED;
import static com.echcherqaoui.orderflow.order.model.enums.CancellationReason.RESERVATION_EXPIRED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.CONFIRMING_INVENTORY;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.EXTENDING_INVENTORY;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INITIALIZING_PAYMENT;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.PAYMENT_SESSION_ACTIVE;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.REVERSED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.REVERSING_INVENTORY;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.REVERSING_PAYMENT;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaService {

    private final OrderRepository orderRepository;
    private final SagaStepLogger sagaStepLogger;
    private final OutboxWriter outboxWriter;
    private final ApplicationEventPublisher eventPublisher;

    @lombok.NonNull
    private Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
              .orElseThrow(() -> new ResourceNotFoundException(ORDER_NOT_FOUND, orderId));
    }

    private boolean isStaleOrDuplicate(@lombok.NonNull Order order,
                                       SagaStep expectedStep,
                                       String eventName) {

        if (order.getCurrentSagaStep() != expectedStep) {
            log.info(
                  "Ignoring duplicate or stale {} for order {}: current step={} (expected step={})",
                  eventName,
                  order.getId(),
                  order.getCurrentSagaStep(),
                  expectedStep
            );

            return true;
        }

        return false;
    }

    private void initiateInventoryCompensation(UUID orderId,
                                               SagaStep expectedStep,
                                               String triggerEventName,
                                               String reason,
                                               String triggerEventId) {
        Order order = getOrder(orderId);
        if (isStaleOrDuplicate(order, expectedStep, triggerEventName)) return;

        order.setStatus(OrderStatus.CANCELLING)
              .setCancellationReason(PAYMENT_FAILED)
              .setCurrentSagaStep(REVERSING_INVENTORY);

        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.failed(expectedStep, Map.of("reason", reason)),
              SagaStepLogger.StepLog.started(REVERSING_INVENTORY, Map.of("reason", reason)),
              triggerEventName,
              triggerEventId
        );

        outboxWriter.publishReleaseInventoryCommand(orderId, triggerEventId, order.getCartId());
        eventPublisher.publishEvent(new OrderPaymentFailedEvent(orderId, reason));
    }

    // OrderSagaService
    @Transactional
    public void handleOrderReserved(@lombok.NonNull UUID orderId,
                                    @lombok.NonNull CreateOrderRequest request,
                                    InventoryServiceClient.@lombok.NonNull ReservationResult reservation) {

        long totalPriceCents = reservation.totalPriceCents();
        String userId = request.userId();

        Order order = new Order()
              .setId(orderId)
              .setUserId(userId)
              .setCartId(request.cartId())
              .setStatus(OrderStatus.PENDING)
              .setCurrentSagaStep(INITIALIZING_PAYMENT)
              .setTotalAmountCents(totalPriceCents);

        for (String itemId : reservation.reservedItemIds())
            order.addItem(new OrderItem().setItemId(UUID.fromString(itemId)));

        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.completed(INVENTORY_RESERVED, Map.of("userId", userId)),
              SagaStepLogger.StepLog.started(INITIALIZING_PAYMENT, Map.of("totalPriceCents", totalPriceCents)),
              null,
              null
        );

        outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents);
    }

    @Transactional
    public void handlePaymentFailed(@lombok.NonNull UUID orderId,
                                    @lombok.NonNull String reason,
                                    @lombok.NonNull String triggerEventId) {
        // Handle payment charge failure from active payment state
        initiateInventoryCompensation(
              orderId,
              PAYMENT_SESSION_ACTIVE,
              "PaymentFailedEvent",
              reason,
              triggerEventId
        );
    }

    @Transactional
    public void handlePaymentInitializationFailed(@lombok.NonNull UUID orderId,
                                                  @lombok.NonNull String failureReason,
                                                  @lombok.NonNull String triggerEventId) {
        // Handle failure during intent creation
        initiateInventoryCompensation(
              orderId,
              INITIALIZING_PAYMENT,
              "PaymentInitializationFailedEvent",
              failureReason,
              triggerEventId
        );
    }

    @Transactional
    public void handlePaymentInitiated(@lombok.NonNull UUID orderId,
                                       @lombok.NonNull String paymentIntentId,
                                       @lombok.NonNull String clientSecret,
                                       @lombok.NonNull String triggerEventId) {
        Order order = getOrder(orderId);
        if (isStaleOrDuplicate(order, INITIALIZING_PAYMENT, "PaymentInitiatedEvent")) return;

        order.setPaymentIntentId(paymentIntentId)
              .setCurrentSagaStep(EXTENDING_INVENTORY);
        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.completed(INITIALIZING_PAYMENT, Map.of("paymentIntentId", paymentIntentId)),
              SagaStepLogger.StepLog.started(EXTENDING_INVENTORY, Map.of("paymentIntentId", paymentIntentId)),
              "PaymentInitiatedEvent",
              triggerEventId
        );

        outboxWriter.publishExtendReservationCommand(orderId, triggerEventId, order.getCartId());

        eventPublisher.publishEvent(new OrderPaymentSessionActiveEvent(orderId, paymentIntentId, clientSecret));
    }

    @Transactional
    public void handleInventoryReleased(@lombok.NonNull UUID orderId,
                                        @lombok.NonNull String cartId,
                                        @lombok.NonNull String triggerEventId) {
        Order order = getOrder(orderId);
        if (isStaleOrDuplicate(order, REVERSING_INVENTORY, "InventoryReleasedEvent")) return;

        order.setStatus(OrderStatus.CANCELLED)
              .setCurrentSagaStep(SagaStep.REVERSED);

        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.completed(REVERSING_INVENTORY, Map.of("cartId", cartId)),
              SagaStepLogger.StepLog.completed(SagaStep.REVERSED, Map.of("cartId", cartId)),
              "InventoryReleasedEvent",
              triggerEventId
        );

        // Outbox: Publish terminal OrderCancelledEvent for downstream analytics/audit
        outboxWriter.publishOrderCancelledEvent(orderId, triggerEventId, order.getCancellationReason().name());
    }

    @Transactional
    public void handleReservationExtensionFailed(@lombok.NonNull UUID orderId,
                                                 String messageId,
                                                 @lombok.NonNull String cartId,
                                                 @lombok.NonNull String reason,
                                                 @lombok.NonNull String triggerEventId) {
        Order order = getOrder(orderId);
        if (isStaleOrDuplicate(order, EXTENDING_INVENTORY, "ReservationExtensionFailedEvent")) return;

        order.setStatus(OrderStatus.CANCELLING)
              .setCancellationReason(RESERVATION_EXPIRED)
              .setCurrentSagaStep(REVERSING_PAYMENT);
        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.failed(EXTENDING_INVENTORY, Map.of("reason", reason)),
              SagaStepLogger.StepLog.started(REVERSING_PAYMENT, Map.of("cartId", cartId)),
              "ReservationExtensionFailedEvent",
              triggerEventId
        );

        outboxWriter.publishCancelPaymentCommand(orderId, messageId, order.getPaymentIntentId(), reason);
        eventPublisher.publishEvent(new ReservationExtensionFailedOrderEvent(orderId, reason));
    }

    @Transactional
    public void handleReservationExtended(@lombok.NonNull UUID orderId,
                                          @lombok.NonNull String cartId,
                                          @lombok.NonNull Instant newExpiresAt,
                                          @lombok.NonNull String triggerEventId) {
        Order order = getOrder(orderId);
        if (isStaleOrDuplicate(order, EXTENDING_INVENTORY, "ReservationExtendedEvent")) return;

        order.setCurrentSagaStep(PAYMENT_SESSION_ACTIVE);
        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.completed(EXTENDING_INVENTORY, Map.of("cartId", cartId, "newExpiresAt", String.valueOf(newExpiresAt.getEpochSecond()))),
              SagaStepLogger.StepLog.started(PAYMENT_SESSION_ACTIVE, Map.of("cartId", cartId)),
              "ReservationExtendedEvent",
              triggerEventId
        );

        eventPublisher.publishEvent(new ReservationExtendedOrderEvent(orderId, newExpiresAt));
    }


    @Transactional
    public void handlePaymentCharged(@lombok.NonNull UUID orderId,
                                     @lombok.NonNull String paymentIntentId,
                                     @lombok.NonNull String triggerEventId) {
        Order order = getOrder(orderId);
        if (isStaleOrDuplicate(order, PAYMENT_SESSION_ACTIVE, "PaymentChargedEvent")) return;

        order.setCurrentSagaStep(CONFIRMING_INVENTORY);
        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.completed(PAYMENT_SESSION_ACTIVE, Map.of("paymentIntentId", paymentIntentId)),
              SagaStepLogger.StepLog.started(CONFIRMING_INVENTORY, Map.of("paymentIntentId", paymentIntentId)),
              "PaymentChargedEvent",
              triggerEventId
        );

        outboxWriter.publishConfirmReservationCommand(orderId, triggerEventId, order.getCartId());
    }

    @Transactional
    public void handlePaymentCancelled(@lombok.NonNull UUID orderId,
                                       String paymentIntentId,
                                       @lombok.NonNull String triggerEventId) {
        Order order = getOrder(orderId);

        // Guard: Enforce order is in CANCELLING status and REVERSING_PAYMENT step
        if (isStaleOrDuplicate(order, REVERSING_PAYMENT, "PaymentCancelledEvent")) return;

        // State Update: Finalize terminal cancelled state
        order.setStatus(OrderStatus.CANCELLED)
              .setCurrentSagaStep(REVERSED);

        orderRepository.save(order);

        sagaStepLogger.logTransition(
              orderId,
              SagaStepLogger.StepLog.completed(REVERSING_PAYMENT, Map.of("paymentIntentId", paymentIntentId != null ? paymentIntentId : "N/A")),
              SagaStepLogger.StepLog.completed(SagaStep.REVERSED, Map.of("reason", order.getCancellationReason().name())),
              "PaymentCancelledEvent",
              triggerEventId
        );

        // Outbox: Publish terminal OrderCancelledEvent for downstream analytics/audit
        outboxWriter.publishOrderCancelledEvent(orderId, triggerEventId, order.getCancellationReason().name());
    }

}