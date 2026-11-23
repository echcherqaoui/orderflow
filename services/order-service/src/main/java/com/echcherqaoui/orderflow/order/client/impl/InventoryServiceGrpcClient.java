package com.echcherqaoui.orderflow.order.client.impl;

import com.echcherqaoui.orderflow.exception.grpc.DownstreamDependencyException;
import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryRequest;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryResponse;
import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.exception.domain.InsufficientStockException;
import com.echcherqaoui.orderflow.order.exception.domain.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.echcherqaoui.orderflow.order.exception.code.OrderErrorCode.ITEM_NOT_FOUND;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryServiceGrpcClient implements InventoryServiceClient {

    private static final String SERVICE_NAME = "inventory-service";

    // Deadline covers the reservation round-trip inside the POST /orders request
    private static final long DEADLINE_SECONDS = 3;

    private final InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    // Breaker is OPEN: fail fast, without calling inventory
    @SuppressWarnings("unused") // invoked reflectively by Resilience4j via fallbackMethod
    private ReservationResult reserveFallback(String cartId,
                                              List<String> itemIds,
                                              CallNotPermittedException e) {
        log.warn("Inventory circuit open, rejecting cartId={}", cartId);
        throw new DownstreamDependencyException(
              SERVICE_NAME,
              Status.Code.UNAVAILABLE,
              "Inventory circuit breaker is open",
              e
        );
    }

    @CircuitBreaker(name = "inventory", fallbackMethod = "reserveFallback")
    @Override
    public ReservationResult reserveInventory(String cartId, List<String> itemIds) {
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId(cartId)
              .addAllItemIds(itemIds)
              .build();

        try {
            ReserveInventoryResponse response = inventoryStub
                  .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                  .reserveInventory(request);

            return new ReservationResult(response.getReservedItemIdsList(), response.getTotalPriceCents());
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();

            switch (code) {
                case FAILED_PRECONDITION ->
                      throw new InsufficientStockException(e);
                case NOT_FOUND ->
                      throw new ResourceNotFoundException(ITEM_NOT_FOUND, e);
                case INVALID_ARGUMENT ->
                      throw new IllegalArgumentException(e.getStatus().getDescription(), e);
                default -> {
                    log.warn("Inventory call failed for cartId={}: {}", cartId, e.getStatus());

                    throw new DownstreamDependencyException(
                          SERVICE_NAME,
                          code,
                          e.getStatus().getDescription(),
                          e
                    );
                }
            }
        }
    }
}