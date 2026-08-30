package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.model.Order;
import com.echcherqaoui.orderflow.order.model.OrderItem;
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

import java.util.List;
import java.util.UUID;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderPersistenceServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private SagaStepLogger sagaStepLogger;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private OrderPersistenceService orderPersistenceService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    private final UUID orderId = UUID.randomUUID();
    private final String cartId = UUID.randomUUID().toString();
    private final String userId = "user-789";
    private final String item1IdStr = UUID.randomUUID().toString();
    private final String item2IdStr = UUID.randomUUID().toString();
    private final List<String> reservedItemIds = List.of(item1IdStr, item2IdStr);
    private final long totalPriceCents = 12500L;

    private CreateOrderRequest request;
    private InventoryServiceClient.ReservationResult reservation;

    @BeforeEach
    void setUp() {
        request = new CreateOrderRequest(cartId, userId, List.of(item1IdStr, item2IdStr));
        reservation = new InventoryServiceClient.ReservationResult(reservedItemIds, totalPriceCents);
    }

    @Nested
    @DisplayName("persistReservedOrder()")
    class PersistReservedOrder {

        @Test
        @DisplayName("successful invocation maps entity, saves order, logs saga step, and publishes outbox command")
        void persistReservedOrder_success_persistsOrderLogsSagaAndPublishesOutbox() {
            given(orderRepository.save(any(Order.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            orderPersistenceService.persistReservedOrder(orderId, request, reservation);

            then(orderRepository).should().save(orderCaptor.capture());
            Order savedOrder = orderCaptor.getValue();

            assertThat(savedOrder).isNotNull();
            assertThat(savedOrder.getId()).isEqualTo(orderId);
            assertThat(savedOrder.getUserId()).isEqualTo(userId);
            assertThat(savedOrder.getCartId()).isEqualTo(cartId);
            assertThat(savedOrder.getCurrentSagaStep()).isEqualTo(INVENTORY_RESERVED);
            assertThat(savedOrder.getTotalAmountCents()).isEqualTo(totalPriceCents);
            assertThat(savedOrder.getItems())
                  .hasSize(2)
                  .extracting(OrderItem::getItemId)
                  .containsExactlyInAnyOrder(UUID.fromString(item1IdStr), UUID.fromString(item2IdStr));

            then(sagaStepLogger).should().logStep(
                  orderId,
                  INVENTORY_RESERVED,
                  SUCCEEDED,
                  null,
                  null
            );

            then(outboxWriter).should().publishChargePaymentCommand(orderId, userId, totalPriceCents);
        }

        @Test
        @DisplayName("repository save failure propagates exception and halts step logging and outbox publishing")
        void persistReservedOrder_repositorySaveFails_propagatesExceptionAndAborts() {
            RuntimeException dbException = new RuntimeException("Database connection failure");
            given(orderRepository.save(any(Order.class))).willThrow(dbException);

            assertThatThrownBy(() -> orderPersistenceService.persistReservedOrder(orderId, request, reservation))
                  .isSameAs(dbException);

            then(orderRepository).should().save(any(Order.class));
            verifyNoInteractions(sagaStepLogger, outboxWriter);
        }

        @Test
        @DisplayName("saga step logging failure propagates exception and aborts outbox publishing")
        void persistReservedOrder_sagaStepLoggerFails_propagatesExceptionAndAbortsOutbox() {
            RuntimeException logException = new RuntimeException("Audit log failure");
            given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));
            willThrow(logException)
                  .given(sagaStepLogger)
                  .logStep(orderId, INVENTORY_RESERVED, SUCCEEDED, null, null);

            assertThatThrownBy(() -> orderPersistenceService.persistReservedOrder(orderId, request, reservation))
                  .isSameAs(logException);

            then(orderRepository).should().save(any(Order.class));
            then(sagaStepLogger).should().logStep(orderId, INVENTORY_RESERVED, SUCCEEDED, null, null);
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publishing failure propagates exception after order save and saga step log")
        void persistReservedOrder_outboxWriterFails_propagatesException() {
            RuntimeException outboxException = new RuntimeException("Protobuf serialization error");
            given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .publishChargePaymentCommand(orderId, userId, totalPriceCents);

            assertThatThrownBy(() -> orderPersistenceService.persistReservedOrder(orderId, request, reservation))
                  .isSameAs(outboxException);

            then(orderRepository).should().save(any(Order.class));
            then(sagaStepLogger).should().logStep(orderId, INVENTORY_RESERVED, SUCCEEDED, null, null);
            then(outboxWriter).should().publishChargePaymentCommand(orderId, userId, totalPriceCents);
        }
    }
}