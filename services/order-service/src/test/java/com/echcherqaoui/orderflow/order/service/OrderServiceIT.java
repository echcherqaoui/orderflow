package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryRequest;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryResponse;
import com.echcherqaoui.orderflow.order.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.exception.domain.CartAlreadyProcessedException;
import com.echcherqaoui.orderflow.order.exception.domain.InsufficientStockException;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class OrderServiceIT extends AbstractIntegrationTest {

    private static final String IN_PROCESS_SERVER_NAME = "test-inventory-service";

    // Swapped per test to control what the fake inventory-service returns —
    // avoids standing up a new server per scenario.
    private static BiConsumer<ReserveInventoryRequest, StreamObserver<ReserveInventoryResponse>> handler;

    private static Server inventoryServer;

    @BeforeAll
    static void startInventoryServer() throws IOException {
        // Fake inventory-service: delegates each call to whatever `handler` the current test set.
        inventoryServer = InProcessServerBuilder.forName(IN_PROCESS_SERVER_NAME)
              .directExecutor()
              .addService(new InventoryServiceGrpc.InventoryServiceImplBase() {
                  @Override
                  public void reserveInventory(ReserveInventoryRequest request,
                                               StreamObserver<ReserveInventoryResponse> responseObserver) {
                      handler.accept(request, responseObserver);
                  }
              }).build()
              .start();
    }

    @AfterAll
    static void stopInventoryServer() {
        inventoryServer.shutdownNow();
    }

    @DynamicPropertySource
    static void overrideInventoryChannel(@NonNull DynamicPropertyRegistry registry) {
        // Redirects the real inventoryServiceBlockingStub bean to the fake
        // in-process inventory-service started in startInventoryServer().
        registry.add("spring.grpc.client.channels.inventory-service.address",
              () -> "in-process:" + IN_PROCESS_SERVER_NAME);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("happy path: reserves inventory, persists order, returns a live SseEmitter")
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