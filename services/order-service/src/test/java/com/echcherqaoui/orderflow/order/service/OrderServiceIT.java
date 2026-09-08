package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryRequest;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryResponse;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.exception.domain.CartAlreadyProcessedException;
import com.echcherqaoui.orderflow.order.exception.domain.InsufficientStockException;
import com.echcherqaoui.orderflow.order.model.enums.OrderStatus;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
import com.echcherqaoui.orderflow.order.repository.OrderSagaHistoryRepository;
import com.echcherqaoui.orderflow.order.support.WithPostgres;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OrderServiceIT implements WithPostgres {

    private static final String IN_PROCESS_SERVER_NAME = "test-inventory-service";

    private static BiConsumer<ReserveInventoryRequest, StreamObserver<ReserveInventoryResponse>> handler;

    private static Server inventoryServer;

    @BeforeAll
    static void startInventoryServer() throws IOException {
        inventoryServer = InProcessServerBuilder.forName(IN_PROCESS_SERVER_NAME)
              .directExecutor()
              .addService(new InventoryServiceGrpc.InventoryServiceImplBase() {
                  @Override
                  public void reserveInventory(ReserveInventoryRequest request,
                                               StreamObserver<ReserveInventoryResponse> responseObserver) {
                      if (handler != null) {
                          handler.accept(request, responseObserver);
                      } else {
                          responseObserver.onError(Status.UNAVAILABLE
                                .withDescription("No handler set for test")
                                .asRuntimeException());
                      }
                  }
              }).build()
              .start();
    }

    @AfterAll
    static void stopInventoryServer() {
        if (inventoryServer != null) {
            inventoryServer.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void overrideInventoryChannel(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.grpc.client.channels.inventory-service.address",
              () -> "in-process:" + IN_PROCESS_SERVER_NAME);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaHistoryRepository sagaHistoryRepository;

    @BeforeEach
    void setUp() {
        handler = null;
        sagaHistoryRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("happy path: reserves inventory, triggers saga reservation handling, persists order, and returns a live SseEmitter")
    void createOrder_success_persistsOrderAndReturnsEmitter() {
        List<String> itemIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        String cartId = UUID.randomUUID().toString();

        handler = (request, responseObserver) -> {
            assertThat(request.getCartId()).isEqualTo(cartId);
            responseObserver.onNext(ReserveInventoryResponse.newBuilder()
                  .addAllReservedItemIds(itemIds)
                  .setTotalPriceCents(4500L)
                  .build());
            responseObserver.onCompleted();
        };

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", itemIds);

        SseEmitter emitter = orderService.createOrder(request);

        assertThat(emitter).isNotNull();
        assertThat(orderRepository.existsByCartId(cartId)).isTrue();

        var savedOrder = orderRepository.findAll().stream()
              .filter(order -> cartId.equals(order.getCartId()))
              .findFirst();

        assertThat(savedOrder).isPresent();
        assertThat(savedOrder.get().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("cartId already processed throws before touching inventory or persistence again")
    void createOrder_cartAlreadyProcessed_throwsOnSecondAttempt() {
        List<String> itemIds = List.of(UUID.randomUUID().toString());
        String cartId = UUID.randomUUID().toString();

        handler = (request, responseObserver) -> {
            responseObserver.onNext(ReserveInventoryResponse.newBuilder()
                  .addAllReservedItemIds(itemIds)
                  .setTotalPriceCents(1000L)
                  .build());
            responseObserver.onCompleted();
        };

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", itemIds);
        orderService.createOrder(request);

        long countAfterFirst = orderRepository.count();

        assertThatThrownBy(() -> orderService.createOrder(request))
              .isInstanceOf(CartAlreadyProcessedException.class);

        assertThat(orderRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("inventory FAILED_PRECONDITION maps to InsufficientStockException and persists nothing")
    void createOrder_insufficientStock_throwsAndPersistsNothing() {
        String cartId = UUID.randomUUID().toString();

        handler = (request, responseObserver) ->
              responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Out of stock")
                    .asRuntimeException());

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com",
              List.of(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> orderService.createOrder(request))
              .isInstanceOf(InsufficientStockException.class);

        assertThat(orderRepository.existsByCartId(cartId)).isFalse();
    }
}