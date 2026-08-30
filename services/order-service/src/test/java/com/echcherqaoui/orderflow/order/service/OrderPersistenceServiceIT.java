package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.order.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.model.Order;
import com.echcherqaoui.orderflow.order.model.OrderSagaHistory;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import com.google.protobuf.Message;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import jakarta.persistence.EntityManager;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class OrderPersistenceServiceIT extends AbstractIntegrationTest {

    @Autowired
    private OrderPersistenceService orderPersistenceService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean(name = "outboxProtobufSerializer")
    private KafkaProtobufSerializer<Message> outboxProtobufSerializer;

    private List<OrderSagaHistory> findSagaHistory(UUID orderId) {
        return entityManager
              .createQuery(
                    "SELECT h FROM OrderSagaHistory h WHERE h.orderId = :orderId ORDER BY h.id",
                    OrderSagaHistory.class
              ).setParameter("orderId", orderId)
              .getResultList();
    }

    private List<OutboxEvent> findOutboxRows(UUID orderId) {
        return entityManager
              .createQuery(
                    "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :orderId AND e.aggregateType = :aggregateType",
                    OutboxEvent.class
              ).setParameter("orderId", orderId.toString())
              .setParameter("aggregateType", "payment.commands")
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

    @Test
    @Transactional
    @DisplayName("happy path — multiple items: persists Order, OrderItems, saga history, and outbox row atomically")
    void persistReservedOrder_multipleItems_persistsOrderItemsSagaHistoryAndOutboxInOneTransaction() {
        when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
              .thenReturn(new byte[] {0, 0, 0, 0, 1});

        UUID orderId = UUID.randomUUID();
        String userId = "user@example.com";
        String cartId = UUID.randomUUID().toString();
        List<String> reservedItemIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        long totalPriceCents = 4998L;

        CreateOrderRequest request = new CreateOrderRequest(cartId, userId, reservedItemIds);
        InventoryServiceClient.ReservationResult reservation =
              new InventoryServiceClient.ReservationResult(reservedItemIds, totalPriceCents);

        orderPersistenceService.persistReservedOrder(orderId, request, reservation);

        entityManager.flush();
        entityManager.clear();

        Order savedOrder = findOrderWithItems(orderId);
        assertThat(savedOrder.getUserId()).isEqualTo(userId);
        assertThat(savedOrder.getCartId()).isEqualTo(cartId);
        assertThat(savedOrder.getCurrentSagaStep()).isEqualTo(SagaStep.INVENTORY_RESERVED);
        assertThat(savedOrder.getTotalAmountCents()).isEqualTo(totalPriceCents);
        assertThat(savedOrder.getItems())
              .extracting(item -> item.getItemId().toString())
              .containsExactlyInAnyOrderElementsOf(reservedItemIds);

        List<OrderSagaHistory> history = findSagaHistory(orderId);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getStep()).isEqualTo(SagaStep.INVENTORY_RESERVED);
        assertThat(history.getFirst().getStatus()).isEqualTo(SagaStepStatus.SUCCEEDED);
        assertThat(history.getFirst().getTriggerEvent()).isNull();
        assertThat(history.getFirst().getTriggerEventId()).isNull();
        assertThat(history.getFirst().getMetadata()).isNull();

        List<OutboxEvent> outboxRows = findOutboxRows(orderId);
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.getFirst().getEventType()).isEqualTo("ChargePaymentCommand");
        assertThat(outboxRows.getFirst().getPayload()).isNotEmpty();
    }

    @Test
    @Transactional
    @DisplayName("single item: persists exactly one OrderItem")
    void persistReservedOrder_singleItem_persistsSuccessfully() {
        when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
              .thenReturn(new byte[] {0, 0, 0, 0, 1});

        UUID orderId = UUID.randomUUID();
        String cartId = UUID.randomUUID().toString();
        List<String> reservedItemIds = List.of(UUID.randomUUID().toString());

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", reservedItemIds);
        InventoryServiceClient.ReservationResult reservation =
              new InventoryServiceClient.ReservationResult(reservedItemIds, 1999L);

        orderPersistenceService.persistReservedOrder(orderId, request, reservation);

        entityManager.flush();
        entityManager.clear();

        Order savedOrder = entityManager.find(Order.class, orderId);
        assertThat(savedOrder).isNotNull();
        assertThat(savedOrder.getItems()).hasSize(1);
        assertThat(savedOrder.getItems().getFirst().getItemId()).hasToString(reservedItemIds.getFirst());
    }

    @Test
    @Transactional
    @DisplayName("zero-cost reservation: persists Order with totalAmountCents = 0")
    void persistReservedOrder_zeroTotalPriceCents_persistsWithZeroAmount() {
        when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
              .thenReturn(new byte[] {0, 0, 0, 0, 1});

        UUID orderId = UUID.randomUUID();
        String cartId = UUID.randomUUID().toString();
        List<String> reservedItemIds = List.of(UUID.randomUUID().toString());

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", reservedItemIds);
        InventoryServiceClient.ReservationResult reservation =
              new InventoryServiceClient.ReservationResult(reservedItemIds, 0L);

        orderPersistenceService.persistReservedOrder(orderId, request, reservation);

        entityManager.flush();
        entityManager.clear();

        Order savedOrder = entityManager.find(Order.class, orderId);
        assertThat(savedOrder).isNotNull();
        assertThat(savedOrder.getTotalAmountCents()).isZero();
    }

    @Test
    @DisplayName("malformed reserved item id: throws before any persistence occurs")
    void persistReservedOrder_malformedItemId_throwsAndPersistsNothing() {
        UUID orderId = UUID.randomUUID();
        String cartId = UUID.randomUUID().toString();
        List<String> reservedItemIds = List.of("not-a-uuid");

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", reservedItemIds);
        InventoryServiceClient.ReservationResult reservation =
              new InventoryServiceClient.ReservationResult(reservedItemIds, 999L);

        assertThatThrownBy(() -> orderPersistenceService.persistReservedOrder(orderId, request, reservation))
              .isInstanceOf(IllegalArgumentException.class);

        entityManager.clear();

        assertThat(entityManager.find(Order.class, orderId)).isNull();
        assertThat(findSagaHistory(orderId)).isEmpty();
        assertThat(findOutboxRows(orderId)).isEmpty();
    }

    @Test
    @DisplayName("outbox serialization failure rolls back the entire transaction — no Order, no saga history, no outbox row")
    void persistReservedOrder_outboxSerializationFails_rollsBackEntireTransaction() {
        when(outboxProtobufSerializer.serialize(anyString(), any(Message.class)))
              .thenThrow(new SerializationException("schema registry unreachable"));

        UUID orderId = UUID.randomUUID();
        String cartId = UUID.randomUUID().toString();
        List<String> reservedItemIds = List.of(UUID.randomUUID().toString());

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", reservedItemIds);
        InventoryServiceClient.ReservationResult reservation =
              new InventoryServiceClient.ReservationResult(reservedItemIds, 1500L);

        assertThatThrownBy(() -> orderPersistenceService.persistReservedOrder(orderId, request, reservation))
              .isInstanceOf(SerializationException.class);

        entityManager.clear();

        assertThat(entityManager.find(Order.class, orderId)).isNull();
        assertThat(findSagaHistory(orderId)).isEmpty();
        assertThat(findOutboxRows(orderId)).isEmpty();
    }
}