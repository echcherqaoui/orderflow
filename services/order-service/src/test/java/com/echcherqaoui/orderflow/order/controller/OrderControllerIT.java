package com.echcherqaoui.orderflow.order.controller;

import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryRequest;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryResponse;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.support.WithPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles
class OrderControllerIT implements WithPostgres {

    private static final String IN_PROCESS_SERVER_NAME = "test-inventory-service-controller";

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
        registry.add("spring.grpc.client.channels.inventory-service.address",
              () -> "in-process:" + IN_PROCESS_SERVER_NAME);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("valid request returns 200 OK with text/event-stream content type")
    void createOrder_validRequest_returns200OkWithEventStream() throws Exception {
        List<String> itemIds = List.of(UUID.randomUUID().toString());
        String cartId = UUID.randomUUID().toString();

        handler = (req, responseObserver) -> {
            responseObserver.onNext(ReserveInventoryResponse.newBuilder()
                  .addAllReservedItemIds(itemIds)
                  .setTotalPriceCents(1999L)
                  .build());
            responseObserver.onCompleted();
        };

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", itemIds);

        mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
              ).andExpect(request().asyncStarted())
              .andExpect(status().isOk())
              .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    @DisplayName("blank cartId fails DTO validation, returns 400 with validation errors")
    void createOrder_blankCartId_returns400WithValidationErrors() throws Exception {
        CreateOrderRequest invalidRequest =
              new CreateOrderRequest("", "user@example.com", List.of(UUID.randomUUID().toString()));

        mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
              ).andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").isNotEmpty())
              .andExpect(jsonPath("$.validationErrors").isNotEmpty());
    }

    @Test
    @DisplayName("cartId already processed returns 409 with CART_409 error code")
    void createOrder_cartAlreadyProcessed_returns409() throws Exception {
        List<String> itemIds = List.of(UUID.randomUUID().toString());
        String cartId = UUID.randomUUID().toString();

        handler = (req, responseObserver) -> {
            responseObserver.onNext(ReserveInventoryResponse.newBuilder()
                  .addAllReservedItemIds(itemIds)
                  .setTotalPriceCents(1500L)
                  .build());
            responseObserver.onCompleted();
        };

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com", itemIds);

        mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
              ).andExpect(request().asyncStarted());

        mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
              ).andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("CART_409"))
              .andExpect(jsonPath("$.message").value("An order has already been processed for cart ID: " + cartId));
    }

    @Test
    @DisplayName("inventory FAILED_PRECONDITION returns 409 with STOCK_409 error code")
    void createOrder_insufficientStock_returns409() throws Exception {
        String cartId = UUID.randomUUID().toString();

        handler = (req, responseObserver) ->
              responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Out of stock")
                    .asRuntimeException());

        CreateOrderRequest request = new CreateOrderRequest(cartId, "user@example.com",
              List.of(UUID.randomUUID().toString()));

        mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
              ).andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("STOCK_409"));
    }
}