package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.exception.domain.ResourceNotFoundException;
import com.echcherqaoui.orderflow.order.messaging.outbox.OutboxWriter;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OrderSagaServiceIT implements WithPostgres {

    @Autowired
    private OrderSagaService orderSagaService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OutboxWriter outboxWriter;

    @MockitoBean(name = "outboxProtobufSerializer")
    private KafkaProtobufSerializer<Message> outboxProtobufSerializer;

    @BeforeEach
    void setUpSerializerStub() {
        lenient().when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
              .thenReturn(new byte[]{0, 1, 2, 3});
    }

    // --- Helper Methods ---
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
              ).setParameter("orderId", orderId)
              .getSingleResult();
    }

    private void assertSingleOutboxEvent(UUID orderId, String aggregateType, String eventType) {
        entityManager.flush();
        entityManager.clear();

        List<OutboxEvent> rows = entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              ).setParameter("orderId", orderId.toString())
              .setParameter("aggregateType", aggregateType)
              .getResultList();

        assertThat(rows).hasSize(1);
        OutboxEvent event = rows.getFirst();
        assertThat(event.getEventType()).isEqualTo(eventType);
        assertThat(event.getPayload()).isNotEmpty();
    }

    private void assertStaleEventIgnored(Order order, Runnable action) {
        int initialHistorySize = findSagaHistory(order.getId()).size();
        SagaStep initialStep = order.getCurrentSagaStep();

        action.run();

        entityManager.flush();
        entityManager.clear();

        Order updated = entityManager.find(Order.class, order.getId());
        assertThat(updated.getCurrentSagaStep()).isEqualTo(initialStep);
        assertThat(findSagaHistory(order.getId())).hasSize(initialHistorySize);
        assertThat(findOutboxRows(order.getId())).isEmpty();
    }

    // --- Test Suites ---

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

            assertSingleOutboxEvent(orderId, "payment.commands", "ChargePaymentCommand");
        }

        @Test
        @DisplayName("malformed reserved item id throws exception and persists nothing")
        void handleOrderReserved_malformedItemId_throwsAndPersistsNothing() {
            UUID orderId = UUID.randomUUID();
            List<String> reservedItemIds = List.of("invalid-uuid");
            CreateOrderRequest request = new CreateOrderRequest(UUID.randomUUID().toString(), "user@example.com", reservedItemIds);
            InventoryServiceClient.ReservationResult reservation = new InventoryServiceClient.ReservationResult(reservedItemIds, 999L);

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
            List<String> reservedItemIds = List.of(UUID.randomUUID().toString());
            CreateOrderRequest request = new CreateOrderRequest(UUID.randomUUID().toString(), "user@example.com", reservedItemIds);
            InventoryServiceClient.ReservationResult reservation = new InventoryServiceClient.ReservationResult(reservedItemIds, 1500L);

            assertThatThrownBy(() -> orderSagaService.handleOrderReserved(orderId, request, reservation))
                  .isInstanceOf(SerializationException.class);

            entityManager.clear();
            assertThat(entityManager.find(Order.class, orderId)).isNull();
            assertThat(findSagaHistory(orderId)).isEmpty();
            assertThat(findOutboxRows(orderId)).isEmpty();
        }
    }

    @Nested
    @DisplayName("handlePaymentInitiated()")
    class HandlePaymentInitiated {

        @Test
        @Transactional
        @DisplayName("valid step INITIALIZING_PAYMENT updates paymentIntentId, sets step EXTENDING_INVENTORY, and writes outbox command")
        void handlePaymentInitiated_validStep_updatesOrderAndPublishesOutbox() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            orderSagaService.handlePaymentInitiated(order.getId(), "pi_99887766", "secret_x1", UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getPaymentIntentId()).isEqualTo("pi_99887766");
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.EXTENDING_INVENTORY);
            assertSingleOutboxEvent(order.getId(), "inventory.commands", "ExtendReservationCommand");
        }

        @Test
        @Transactional
        @DisplayName("stale event for unexpected saga step is ignored without modifying order or creating outbox events")
        void handlePaymentInitiated_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING);
            assertStaleEventIgnored(order, () -> orderSagaService.handlePaymentInitiated(order.getId(), "pi_9999", "secret_9999", UUID.randomUUID().toString()));
        }
    }

    @Nested
    @DisplayName("handlePaymentInitializationFailed()")
    class HandlePaymentInitializationFailed {

        @Test
        @Transactional
        @DisplayName("valid step INITIALIZING_PAYMENT sets status CANCELLING, step REVERSING_INVENTORY, and writes release command")
        void handlePaymentInitializationFailed_validStep_updatesStatusAndPublishesReleaseCommand() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            orderSagaService.handlePaymentInitializationFailed(order.getId(), "CARD_DECLINED", UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_FAILED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_INVENTORY);
            assertSingleOutboxEvent(order.getId(), "inventory.commands", "ReleaseInventoryCommand");
        }
    }

    @Nested
    @DisplayName("handleReservationExtended()")
    class HandleReservationExtended {

        @Test
        @Transactional
        @DisplayName("valid step EXTENDING_INVENTORY updates step to PAYMENT_SESSION_ACTIVE")
        void handleReservationExtended_validStep_updatesSagaStep() {
            Order order = createAndPersistOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            orderSagaService.handleReservationExtended(order.getId(), order.getCartId(), Instant.now().plusSeconds(900), UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.PAYMENT_SESSION_ACTIVE);
        }
    }

    @Nested
    @DisplayName("handleReservationExtensionFailed()")
    class HandleReservationExtensionFailed {

        @Test
        @Transactional
        @DisplayName("valid step EXTENDING_INVENTORY updates status CANCELLING, step REVERSING_PAYMENT, and writes cancel command")
        void handleReservationExtensionFailed_validStep_updatesStatusAndPublishesCancelPayment() {
            Order order = createAndPersistOrder(SagaStep.EXTENDING_INVENTORY, OrderStatus.PENDING);
            order.setPaymentIntentId("pi_12345");
            entityManager.merge(order);

            orderSagaService.handleReservationExtensionFailed(order.getId(), UUID.randomUUID().toString(), order.getCartId(), "LOCK_TIMEOUT", UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.RESERVATION_EXPIRED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);
            assertSingleOutboxEvent(order.getId(), "payment.commands", "CancelPaymentCommand");
        }
    }

    @Nested
    @DisplayName("handlePaymentFailed()")
    class HandlePaymentFailed {

        @Test
        @Transactional
        @DisplayName("valid step PAYMENT_SESSION_ACTIVE sets status CANCELLING, step REVERSING_INVENTORY, and writes ReleaseInventoryCommand outbox row")
        void handlePaymentFailed_validStep_updatesStatusAndPublishesReleaseCommand() {
            Order order = createAndPersistOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING);
            orderSagaService.handlePaymentFailed(order.getId(), "INSUFFICIENT_FUNDS", UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_FAILED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_INVENTORY);
            assertSingleOutboxEvent(order.getId(), "inventory.commands", "ReleaseInventoryCommand");
        }

        @Test
        @Transactional
        @DisplayName("stale PaymentFailedEvent for unexpected saga step is ignored without modifying order or creating outbox events")
        void handlePaymentFailed_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            assertStaleEventIgnored(order, () -> orderSagaService.handlePaymentFailed(order.getId(), "CARD_DECLINED", UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when target order does not exist")
        void handlePaymentFailed_notFound_throwsResourceNotFoundException() {
            UUID orderId = UUID.randomUUID();
            String triggerEventId = UUID.randomUUID().toString();
            assertThatThrownBy(() -> orderSagaService.handlePaymentFailed(orderId, "CARD_DECLINED", triggerEventId))
                  .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("handleInventoryReleased()")
    class HandleInventoryReleased {

        @Test
        @Transactional
        @DisplayName("valid step REVERSING_INVENTORY updates status CANCELLED, step REVERSED, and writes OrderCancelledEvent outbox row")
        void handleInventoryReleased_validStep_completesOrderCancellationAndPublishesCancelledEvent() {
            Order order = createAndPersistOrder(SagaStep.REVERSING_INVENTORY, OrderStatus.CANCELLING);
            order.setCancellationReason(CancellationReason.PAYMENT_FAILED);
            entityManager.merge(order);

            orderSagaService.handleInventoryReleased(order.getId(), order.getCartId(), UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);
            assertSingleOutboxEvent(order.getId(), "order.events", "OrderCancelledIntegrationEvent");
        }
    }

    @Nested
    @DisplayName("handlePaymentCharged()")
    class HandlePaymentCharged {

        @Test
        @Transactional
        @DisplayName("valid step PAYMENT_SESSION_ACTIVE updates step to CONFIRMING_INVENTORY and writes ConfirmReservationCommand outbox row")
        void handlePaymentCharged_validStep_updatesSagaStepAndPublishesConfirmCommand() {
            Order order = createAndPersistOrder(SagaStep.PAYMENT_SESSION_ACTIVE, OrderStatus.PENDING);
            orderSagaService.handlePaymentCharged(order.getId(), "pi_99887766", UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.CONFIRMING_INVENTORY);
            assertSingleOutboxEvent(order.getId(), "inventory.commands", "ConfirmReservationCommand");
        }

        @Test
        @Transactional
        @DisplayName("stale PaymentChargedEvent for unexpected saga step is ignored without side effects")
        void handlePaymentCharged_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            assertStaleEventIgnored(order, () -> orderSagaService.handlePaymentCharged(order.getId(), "pi_stale", UUID.randomUUID().toString()));
        }
    }

    @Nested
    @DisplayName("handlePaymentCancelled()")
    class HandlePaymentCancelled {

        @Test
        @Transactional
        @DisplayName("valid step REVERSING_PAYMENT updates status CANCELLED, step REVERSED, and writes OrderCancelledEvent outbox row")
        void handlePaymentCancelled_validStep_completesOrderCancellationAndPublishesCancelledEvent() {
            Order order = createAndPersistOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            order.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            order.setPaymentIntentId("pi_12345");
            entityManager.merge(order);

            orderSagaService.handlePaymentCancelled(order.getId(), "pi_12345", UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSED);
            assertSingleOutboxEvent(order.getId(), "order.events", "OrderCancelledIntegrationEvent");
        }

        @Test
        @Transactional
        @DisplayName("stale PaymentCancelledEvent for unexpected saga step is ignored without modifying order or creating outbox events")
        void handlePaymentCancelled_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            assertStaleEventIgnored(order, () -> orderSagaService.handlePaymentCancelled(order.getId(), "pi_stale", UUID.randomUUID().toString()));
        }
    }

    @Nested
    @DisplayName("handleInventoryConfirmationFailed()")
    class HandleInventoryConfirmationFailed {

        @Test
        @Transactional
        @DisplayName("valid step CONFIRMING_INVENTORY updates status CANCELLING, step REVERSING_PAYMENT, and writes RefundPaymentCommand outbox row")
        void handleInventoryConfirmationFailed_validStep_initiatesPaymentRefundCompensation() {
            Order order = createAndPersistOrder(SagaStep.CONFIRMING_INVENTORY, OrderStatus.PENDING);
            order.setPaymentIntentId("pi_123456789");
            entityManager.merge(order);

            orderSagaService.handleInventoryConfirmationFailed(order.getId(), "OUT_OF_STOCK", UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLING);
            assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.RESERVATION_EXPIRED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);
            assertSingleOutboxEvent(order.getId(), "payment.commands", "RefundPaymentCommand");
        }

        @Test
        @Transactional
        @DisplayName("stale InventoryConfirmationFailedEvent for unexpected saga step is ignored without side effects")
        void handleInventoryConfirmationFailed_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            assertStaleEventIgnored(order, () -> orderSagaService.handleInventoryConfirmationFailed(order.getId(), "OUT_OF_STOCK", UUID.randomUUID().toString()));
        }
    }

    @Nested
    @DisplayName("Transactional Integrity")
    class TransactionalIntegrity {

        @Test
        @Transactional
        @DisplayName("publishing multiple commands in single transaction persists all records sequentially")
        void publishingMultipleCommands_persistsAllInSameTransaction() {
            UUID orderId = UUID.randomUUID();
            String causationId = UUID.randomUUID().toString();

            outboxWriter.publishChargePaymentCommand(orderId, "user@example.com", 5000L);
            outboxWriter.publishReleaseInventoryCommand(orderId, causationId, "cart-multi");

            entityManager.flush();
            entityManager.clear();

            List<OutboxEvent> events = entityManager
                  .createQuery(
                        "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId ORDER BY e.createdAt ASC",
                        OutboxEvent.class
                  )
                  .setParameter("orderId", orderId.toString())
                  .getResultList();

            assertThat(events).hasSize(2);
            assertThat(events.get(0).getEventType()).isEqualTo("ChargePaymentCommand");
            assertThat(events.get(1).getEventType()).isEqualTo("ReleaseInventoryCommand");
        }

        @Test
        @Transactional
        @DisplayName("transaction rollback discards written outbox entries")
        void transactionRollback_discardsOutboxEntries() {
            UUID orderId = UUID.randomUUID();
            outboxWriter.publishChargePaymentCommand(orderId, "user@example.com", 2000L);
            entityManager.flush();

            TestTransaction.flagForRollback();
            TestTransaction.end();

            TestTransaction.start();
            List<OutboxEvent> rows = entityManager
                  .createQuery("SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId", OutboxEvent.class)
                  .setParameter("orderId", orderId.toString())
                  .getResultList();

            assertThat(rows).isEmpty();
        }
    }
    @Nested
    @DisplayName("handlePaymentRefunded()")
    class HandlePaymentRefunded {

        @Test
        @Transactional
        @DisplayName("valid step REVERSING_PAYMENT updates status CANCELLED, step REFUNDED, records saga history, and writes OrderCancelledIntegrationEvent outbox row")
        void handlePaymentRefunded_validStep_completesOrderCancellationAndPublishesCancelledEvent() {
            Order order = createAndPersistOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            order.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            order.setPaymentIntentId("pi_refunded_123");
            entityManager.merge(order);

            orderSagaService.handlePaymentRefunded(order.getId(), "pi_refunded_123", UUID.randomUUID().toString());

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.REFUNDED);

            List<OrderSagaHistory> history = findSagaHistory(order.getId());
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);
            assertThat(history.get(0).getStatus()).isEqualTo(SagaStepStatus.COMPLETED);
            assertThat(history.get(1).getStep()).isEqualTo(SagaStep.REFUNDED);
            assertThat(history.get(1).getStatus()).isEqualTo(SagaStepStatus.COMPLETED);

            assertSingleOutboxEvent(order.getId(), "order.events", "OrderCancelledIntegrationEvent");
        }

        @Test
        @Transactional
        @DisplayName("stale PaymentRefundedEvent for unexpected saga step is ignored without modifying order, writing history, or creating outbox events")
        void handlePaymentRefunded_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.REFUNDED, OrderStatus.CANCELLED);
            assertStaleEventIgnored(order, () -> orderSagaService.handlePaymentRefunded(order.getId(), "pi_stale", UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when target order does not exist")
        void handlePaymentRefunded_notFound_throwsResourceNotFoundException() {
            UUID orderId = UUID.randomUUID();
            String triggerEventId = UUID.randomUUID().toString();

            assertThatThrownBy(() -> orderSagaService.handlePaymentRefunded(orderId, "pi_123", triggerEventId))
                  .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("handlePaymentRefundFailed()")
    class HandlePaymentRefundFailed {

        @Test
        @Transactional
        @DisplayName("valid step REVERSING_PAYMENT updates status CANCELLED, reason REFUND_FAILED, step MANUAL_INTERVENTION_REQUIRED, records history, and writes outbox event")
        void handlePaymentRefundFailed_validStep_transitionsToManualInterventionAndPublishesOutbox() {
            Order order = createAndPersistOrder(SagaStep.REVERSING_PAYMENT, OrderStatus.CANCELLING);
            order.setCancellationReason(CancellationReason.RESERVATION_EXPIRED);
            order.setPaymentIntentId("pi_refund_fail_123");
            entityManager.merge(order);

            orderSagaService.handlePaymentRefundFailed(order.getId(), "pi_refund_fail_123", "GATEWAY_TIMEOUT", UUID.randomUUID().toString());

            entityManager.flush();
            entityManager.clear();

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(updated.getCancellationReason()).isEqualTo(CancellationReason.REFUND_FAILED);
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.MANUAL_INTERVENTION_REQUIRED);

            List<OrderSagaHistory> history = findSagaHistory(order.getId());
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getStep()).isEqualTo(SagaStep.REVERSING_PAYMENT);
            assertThat(history.get(0).getStatus()).isEqualTo(SagaStepStatus.FAILED);
            assertThat(history.get(1).getStep()).isEqualTo(SagaStep.MANUAL_INTERVENTION_REQUIRED);
            assertThat(history.get(1).getStatus()).isEqualTo(SagaStepStatus.COMPLETED);

            assertSingleOutboxEvent(order.getId(), "order.events", "OrderCancelledIntegrationEvent");
        }

        @Test
        @Transactional
        @DisplayName("stale PaymentRefundFailedEvent for unexpected saga step is ignored without modifying order or side effects")
        void handlePaymentRefundFailed_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.MANUAL_INTERVENTION_REQUIRED, OrderStatus.CANCELLED);
            assertStaleEventIgnored(order, () -> orderSagaService.handlePaymentRefundFailed(order.getId(), "pi_stale", "GATEWAY_TIMEOUT", UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when target order does not exist")
        void handlePaymentRefundFailed_notFound_throwsResourceNotFoundException() {
            UUID orderId = UUID.randomUUID();
            String triggerEventId = UUID.randomUUID().toString();
            assertThatThrownBy(() -> orderSagaService.handlePaymentRefundFailed(orderId, "pi_123", "GATEWAY_TIMEOUT", triggerEventId))
                  .isInstanceOf(ResourceNotFoundException.class);
        }
    }


    @Nested
    @DisplayName("handleInventoryConfirmed()")
    class HandleInventoryConfirmed {

        @Test
        @Transactional
        @DisplayName("valid step CONFIRMING_INVENTORY updates step to GRANTING_ENTITLEMENT, records saga history, and writes GrantEntitlementCommand outbox row")
        void handleInventoryConfirmed_validStep_updatesSagaStepAndPublishesCommand() {
            Order order = createAndPersistOrder(SagaStep.CONFIRMING_INVENTORY, OrderStatus.PENDING);
            orderSagaService.handleInventoryConfirmed(order.getId(), order.getCartId(), UUID.randomUUID().toString());

            Order updated = entityManager.find(Order.class, order.getId());
            assertThat(updated.getCurrentSagaStep()).isEqualTo(SagaStep.GRANTING_ENTITLEMENT);
            assertSingleOutboxEvent(order.getId(), "entitlement.commands", "GrantEntitlementCommand");
        }

        @Test
        @Transactional
        @DisplayName("stale InventoryConfirmedEvent for unexpected saga step is ignored without modifying order or creating outbox events")
        void handleInventoryConfirmed_staleStep_ignoresEvent() {
            Order order = createAndPersistOrder(SagaStep.INITIALIZING_PAYMENT, OrderStatus.PENDING);
            assertStaleEventIgnored(order, () -> orderSagaService.handleInventoryConfirmed(order.getId(), order.getCartId(), UUID.randomUUID().toString()));
        }
    }
}