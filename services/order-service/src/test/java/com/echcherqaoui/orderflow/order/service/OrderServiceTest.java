package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.exception.domain.CartAlreadyProcessedException;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
import com.echcherqaoui.orderflow.order.sse.SseEmitterRegistry;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryServiceClient inventoryServiceClient;

    @Mock
    private OrderPersistenceService orderPersistenceService;

    @Mock
    private SseEmitterRegistry emitterRegistry;

    @Mock
    private SseEmitter mockSseEmitter;

    @InjectMocks
    private OrderService orderService;

    @Captor
    private ArgumentCaptor<UUID> orderIdCaptor;

    private CreateOrderRequest request;
    private InventoryServiceClient.ReservationResult reservationResult;
    private final String cartId = UUID.randomUUID().toString();
    private final List<String> itemIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());

    @BeforeEach
    void setUp() {
        String userId = "user-123";
        request = new CreateOrderRequest(cartId, userId, itemIds);
        reservationResult = new InventoryServiceClient.ReservationResult(itemIds, 5000L);
    }

    @Nested
    @DisplayName("createOrder()")
    class CreateOrder {

        @Test
        @DisplayName("null request throws NullPointerException")
        void createOrder_nullRequest_throwsNullPointerException() {
            assertThatThrownBy(() -> orderService.createOrder(null))
                  .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("existing cartId throws CartAlreadyProcessedException without invoking gRPC or SSE")
        void createOrder_existingCartId_throwsCartAlreadyProcessedException() {
            given(orderRepository.existsByCartId(cartId)).willReturn(true);

            assertThatThrownBy(() -> orderService.createOrder(request))
                  .isInstanceOf(CartAlreadyProcessedException.class);

            then(orderRepository).should().existsByCartId(cartId);
            verifyNoInteractions(inventoryServiceClient, emitterRegistry, orderPersistenceService);
        }

        @Test
        @DisplayName("inventory reservation failure propagates exception without registering SSE emitter")
        void createOrder_inventoryReservationFails_propagatesException() {
            given(orderRepository.existsByCartId(cartId)).willReturn(false);
            given(inventoryServiceClient.reserveInventory(cartId, itemIds))
                  .willThrow(new RuntimeException("Inventory service unavailable"));

            assertThatThrownBy(() -> orderService.createOrder(request))
                  .isInstanceOf(RuntimeException.class)
                  .hasMessage("Inventory service unavailable");

            then(orderRepository).should().existsByCartId(cartId);
            then(inventoryServiceClient).should().reserveInventory(cartId, itemIds);
            verifyNoInteractions(emitterRegistry, orderPersistenceService);
        }

        @Test
        @DisplayName("persistence failure cleans up SSE emitter and rethrows exception")
        void createOrder_persistenceFails_cleansUpSseEmitterAndRethrows() {
            RuntimeException persistenceException = new RuntimeException("Database error");

            given(orderRepository.existsByCartId(cartId)).willReturn(false);
            given(inventoryServiceClient.reserveInventory(cartId, itemIds)).willReturn(reservationResult);
            given(emitterRegistry.register(any(UUID.class))).willReturn(mockSseEmitter);
            willThrow(persistenceException)
                  .given(orderPersistenceService)
                  .persistReservedOrder(any(UUID.class), eq(request), eq(reservationResult));

            assertThatThrownBy(() -> orderService.createOrder(request))
                  .isSameAs(persistenceException);

            then(emitterRegistry).should().register(orderIdCaptor.capture());
            UUID generatedOrderId = orderIdCaptor.getValue();

            then(orderPersistenceService).should().persistReservedOrder(generatedOrderId, request, reservationResult);
            then(emitterRegistry).should().remove(generatedOrderId);
            then(mockSseEmitter).should().completeWithError(persistenceException);
        }

        @Test
        @DisplayName("successful order creation registers SSE emitter, persists order, and returns emitter")
        void createOrder_success_registersSsePersistsOrderAndReturnsEmitter() {
            given(orderRepository.existsByCartId(cartId)).willReturn(false);
            given(inventoryServiceClient.reserveInventory(cartId, itemIds)).willReturn(reservationResult);
            given(emitterRegistry.register(any(UUID.class))).willReturn(mockSseEmitter);

            SseEmitter result = orderService.createOrder(request);

            assertThat(result).isNotNull().isEqualTo(mockSseEmitter);

            then(orderRepository).should().existsByCartId(cartId);
            then(inventoryServiceClient).should().reserveInventory(cartId, itemIds);
            then(emitterRegistry).should().register(orderIdCaptor.capture());

            UUID generatedOrderId = orderIdCaptor.getValue();
            assertThat(generatedOrderId).isNotNull();

            then(orderPersistenceService).should().persistReservedOrder(generatedOrderId, request, reservationResult);
            then(emitterRegistry).should(never()).remove(any());
            then(mockSseEmitter).should(never()).completeWithError(any());
        }
    }
}