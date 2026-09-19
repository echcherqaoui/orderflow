package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.exception.domain.ResourceNotFoundException;
import com.echcherqaoui.orderflow.order.model.Order;
import com.echcherqaoui.orderflow.order.model.OrderSagaHistory;
import com.echcherqaoui.orderflow.order.model.enums.CancellationReason;
import com.echcherqaoui.orderflow.order.model.enums.OrderStatus;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import com.echcherqaoui.orderflow.order.support.WithPostgres;
import com.google.protobuf.Message;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import jakarta.persistence.EntityManager;
import org.apache.commons.lang3.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OrderSagaServiceIT implements WithPostgres {

    @Autowired
    private OrderSagaService orderSagaService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean(name = "outboxProtobufSerializer")
    private KafkaProtobufSerializer<Message> outboxProtobufSerializer;

    @Nested
    @DisplayName("Order Lookup")
    class OrderLookup {

        @Test
        @DisplayName("throws ResourceNotFoundException when target order does not exist")
        void getOrder_notFound_throwsResourceNotFoundException() {
            UUID randomOrderId = UUID.randomUUID();
            String triggerEventId = UUID.randomUUID().toString();

            assertThatThrownBy(() -> orderSagaService.handlePaymentInitiated(randomOrderId, "pi_123", "secret_123", triggerEventId))
                  .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("handleOrderReserved()")
    class HandleOrderReserved {

        @Test
        @Transactional
        @DisplayName("persists Order, OrderItems, 2 saga history records, and outbox command atomically")
        void handleOrderReserved_validRequest_persistsAllEntitiesAndOutboxRow() {
            when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
                  .thenReturn(new byte[]{0, 0, 0, 0, 1});

            UUID orderId = UUID.randomUUID();
            String userId = "user@example.com";
            String cartId = UUID.randomUUID().toString();
            List<String> reservedItemIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            long totalPriceCents = 4998L;

            CreateOrderRequest request = new CreateOrderRequest(cartId, userId, reservedItemIds);
            InventoryServiceClient.ReservationResult reservation =
                  new InventoryServiceClient.ReservationResult(reservedItemIds, totalPriceCents);

            orderSagaService.handleOrderReserved(orderId, request, reservation);

            entityManager.flush();
            entityManager.clear();

            Order savedOrder = findOrderWithItems(orderId);
            assertThat(savedOrder.getUserId()).isEqualTo(userId);
            assertThat(savedOrder.getCartId()).isEqualTo(cartId);
            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(savedOrder.getCurrentSagaStep()).isEqualTo(SagaStep.INITIALIZING_PAYMENT);
            assertThat(savedOrder.getTotalAmountCents()).isEqualTo(totalPriceCents);
            assertThat(savedOrder.getItems())
                  .extracting(item -> item.getItemId().toString())
                  .containsExactlyInAnyOrderElementsOf(reservedItemIds);

            List<OrderSagaHistory> history = findSagaHistory(orderId);
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getStep()).isEqualTo(SagaStep.INVENTORY_RESERVED);
            assertThat(history.get(0).getStatus()).isEqualTo(SagaStepStatus.COMPLETED);
            assertThat(history.get(1).getStep()).isEqualTo(SagaStep.INITIALIZING_PAYMENT);
            assertThat(history.get(1).getStatus()).isEqualTo(SagaStepStatus.STARTED);

            List<OutboxEvent> outboxRows = findOutboxRows(orderId);
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.getFirst().getAggregateType()).isEqualTo("payment.commands");
            assertThat(outboxRows.getFirst().getEventType()).isEqualTo("ChargePaymentCommand");
            assertThat(outboxRows.getFirst().getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("malformed reserved item id throws exception and persists nothing")
        void handleOrderReserved_malformedItemId_throwsAndPersistsNothing() {
            UUID orderId = UUID.randomUUID();
            String cartId = UUID.randomUUID().toString();
            List<String> reservedItemIds = List.of("invalid-uuid");

            CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", reservedItemIds);
            InventoryServiceClient.ReservationResult reservation =
                  new InventoryServiceClient.ReservationResult(reservedItemIds, 999L);

            assertThatThrownBy(() -> orderSagaService.handleOrderReserved(orderId, request, reservation))
                  .isInstanceOf(IllegalArgumentException.class);

            entityManager.clear();

            assertThat(entityManager.find(Order.class, orderId)).isNull();
            assertThat(findSagaHistory(orderId)).isEmpty();
            assertThat(findOutboxRows(orderId)).isEmpty();
        }

        @Test
        @DisplayName("outbox serialization failure rolls back order, history, and outbox row")
        void handleOrderReserved_outboxSerializationFails_rollsBackTransaction() {
            when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
                  .thenThrow(new SerializationException("Schema registry unavailable"));

            UUID orderId = UUID.randomUUID();
            String cartId = UUID.randomUUID().toString();
            List<String> reservedItemIds = List.of(UUID.randomUUID().toString());

            CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", reservedItemIds);
            InventoryServiceClient.ReservationResult reservation =
                  new InventoryServiceClient.ReservationResult(reservedItemIds, 1500L);

            assertThatThrownBy(() -> orderSagaService.handleOrderReserved(orderId, request, reservation))
                  .isInstanceOf(SerializationException.class);

            entityManager.clear();

            assertThat(entityManager.find(Order.class, orderId)).isNull();
            assertThat(findSagaHistory(orderId)).isEmpty();
            assertThat(findOutboxRows(orderId)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Saga Step Transitions")
    class SagaStepTransitions {

        @Test
        @Transactional
        @DisplayName("handlePaymentInitiated updates paymentIntentId, sets step EXTENDING_INVENTORY, and writes outbox command")
        void handlePaymentInitiated_validStep_updatesOrderAndPublishesOutbox() {
            when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
                  .thenReturn(new byte[]{0, 1, 2});

            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            String paymentIntentId = "pi_99887766";
            String clientSecret = "pi_99887766_secret_x1y2z3";
            String triggerEventId = UUID.randomUUID().toString();

            orderSagaService.handlePaymentInitiated(order.getId(), paymentIntentId, clientSecret, triggerEventId);

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.EXTENDING_INVENTORY);

            List<OutboxEvent> outboxRows = findOutboxRows(order.getId());
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.getFirst().getAggregateType()).isEqualTo("inventory.commands");
            assertThat(outboxRows.getFirst().getEventType()).isEqualTo("ExtendReservationCommand");
        }

        @Test
        @Transactional
        @DisplayName("handlePaymentInitializationFailed sets status CANCELLING, step REVERSING_INVENTORY, and writes release command")
        void handlePaymentInitializationFailed_validStep_updatesStatusAndPublishesReleaseCommand() {
            when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
                  .thenReturn(new byte[]{0, 1, 2});

            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            String triggerEventId = UUID.randomUUID().toString();

            orderSagaService.handlePaymentInitializationFailed(order.getId(), "CARD_DECLINED", triggerEventId);

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_FAILED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_INVENTORY);

            List<OutboxEvent> outboxRows = findOutboxRows(order.getId());
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.getFirst().getAggregateType()).isEqualTo("inventory.commands");
            assertThat(outboxRows.getFirst().getEventType()).isEqualTo("ReleaseInventoryCommand");
        }

        @Test
        @Transactional
        @DisplayName("handleReservationExtended updates step to PAYMENT_SESSION_ACTIVE")
        void handleReservationExtended_validStep_updatesSagaStep() {
            Order order = createAndPersistOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            Instant newExpiresAt = Instant.now().plusSeconds(900);
            String triggerEventId = UUID.randomUUID().toString();

            orderSagaService.handleReservationExtended(order.getId(), order.getCartId(), newExpiresAt, triggerEventId);

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.PAYMENT_SESSION_ACTIVE);
        }

        @Test
        @Transactional
        @DisplayName("handleReservationExtensionFailed updates status CANCELLING, step REVERSING_PAYMENT, and writes cancel command")
        void handleReservationExtensionFailed_validStep_updatesStatusAndPublishesCancelPayment() {
            when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
                  .thenReturn(new byte[]{0, 1, 2});

            Order order = createAndPersistOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            order.setPaymentIntentId("pi_12345");
            entityManager.merge(order);

            String triggerEventId = UUID.randomUUID().toString();

            orderSagaService.handleReservationExtensionFailed(
                  order.getId(),
                  UUID.randomUUID().toString(),
                  order.getCartId(),
                  "LOCK_TIMEOUT",
                  triggerEventId
            );

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.RESERVATION_EXPIRED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);

            List<OutboxEvent> outboxRows = findOutboxRows(order.getId());
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.getFirst().getAggregateType()).isEqualTo("payment.commands");
            assertThat(outboxRows.getFirst().getEventType()).isEqualTo("CancelPaymentCommand");
        }

        @Test
        @Transactional
        @DisplayName("handleInventoryReleased updates status CANCELLED, step REVERSED, and writes OrderCancelledEvent outbox row")
        void handleInventoryReleased_validStep_completesOrderCancellationAndPublishesCancelledEvent() {
            when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
                  .thenReturn(new byte[]{0, 1, 2});

            Order order = createAndPersistOrder(SagaStep.REVERSING_INVENTORY, OrderStatus.CANCELLING);
            order.setCancellationReason(CancellationReason.PAYMENT_FAILED);
            entityManager.merge(order);

            String triggerEventId = UUID.randomUUID().toString();

            orderSagaService.handleInventoryReleased(order.getId(), order.getCartId(), triggerEventId);

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);

            List<OutboxEvent> outboxRows = findOutboxRows(order.getId());
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.getFirst().getAggregateType()).isEqualTo("order.events");
            assertThat(outboxRows.getFirst().getEventType()).isEqualTo("OrderCancelledIntegrationEvent");
        }

        @Test
        @Transactional
        @DisplayName("handlePaymentCancelled updates status CANCELLED, step REVERSED, and writes OrderCancelledEvent outbox row")
        void handlePaymentCancelled_validStep_completesOrderCancellationAndPublishesCancelledEvent() {
            when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
                  .thenReturn(new byte[]{0, 1, 2});

            Order order = createAndPersistOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            order.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            order.setPaymentIntentId("pi_12345");
            entityManager.merge(order);

            String triggerEventId = UUID.randomUUID().toString();

            orderSagaService.handlePaymentCancelled(order.getId(), "pi_12345", triggerEventId);

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);

            List<OutboxEvent> outboxRows = findOutboxRows(order.getId());
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.getFirst().getAggregateType()).isEqualTo("order.events");
            assertThat(outboxRows.getFirst().getEventType()).isEqualTo("OrderCancelledIntegrationEvent");
        }
    }

    @Nested
    @DisplayName("Idempotency & Stale Event Protection")
    class IdempotencyProtection {

        @Test
        @Transactional
        @DisplayName("stale event for unexpected saga step is ignored without modifying order or creating outbox events")
        void handlePaymentInitiated_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING);
            int initialHistorySize = findSagaHistory(order.getId()).size();

            orderSagaService.handlePaymentInitiated(order.getId(), "pi_9999", "secret_9999", UUID.randomUUID().toString());

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.PAYMENT_SESSION_ACTIVE);
            assertThat(findSagaHistory(order.getId())).hasSize(initialHistorySize);
            assertThat(findOutboxRows(order.getId())).isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("stale PaymentCancelledEvent for unexpected saga step is ignored without modifying order or creating outbox events")
        void handlePaymentCancelled_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            int initialHistorySize = findSagaHistory(order.getId()).size();

            orderSagaService.handlePaymentCancelled(order.getId(), "pi_stale", UUID.randomUUID().toString());

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.INITIALIZING_PAYMENT);
            assertThat(findSagaHistory(order.getId())).hasSize(initialHistorySize);
            assertThat(findOutboxRows(order.getId())).isEmpty();
        }
    }

    private Order createAndPersistOrder(SagaStep step, OrderStatus status) {
        Order order = new Order()
              .setId(UUID.randomUUID())
              .setUserId("user@example.com")
              .setCartId(UUID.randomUUID().toString())
              .setStatus(status)
              .setCurrentSagaStep(step)
              .setTotalAmountCents(2500L);
        entityManager.persist(order);
        return order;
    }

    private List<OrderSagaHistory> findSagaHistory(UUID orderId) {
        return entityManager
              .createQuery(
                    "SELECT h FROM OrderSagaHistory h WHERE h.orderId = :orderId ORDER BY h.id",
                    OrderSagaHistory.class
              )
              .setParameter("orderId", orderId)
              .getResultList();
    }

    private List<OutboxEvent> findOutboxRows(UUID orderId) {
        return entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId",
                    OutboxEvent.class
              )
              .setParameter("orderId", orderId.toString())
              .getResultList();
    }

    private Order findOrderWithItems(UUID orderId) {
        return entityManager
              .createQuery(
                    "SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :orderId",
                    Order.class
              )
              .setParameter("orderId", orderId)
              .getSingleResult();
    }
}