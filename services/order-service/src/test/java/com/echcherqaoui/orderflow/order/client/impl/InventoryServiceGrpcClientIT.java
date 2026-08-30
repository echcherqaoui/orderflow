package com.echcherqaoui.orderflow.order.client.impl;

import com.echcherqaoui.orderflow.exception.grpc.DownstreamDependencyException;
import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryRequest;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryResponse;
import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.exception.code.OrderErrorCode;
import com.echcherqaoui.orderflow.order.exception.domain.InsufficientStockException;
import com.echcherqaoui.orderflow.order.exception.domain.ResourceNotFoundException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryServiceGrpcClientIT {

    private final List<Server> servers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : channels)
            channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);

        for (Server server : servers)
            server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
    }

    private InventoryServiceGrpcClient buildClient(InventoryServiceGrpc.InventoryServiceImplBase impl) throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        Server server = InProcessServerBuilder.forName(serverName)
              .directExecutor()
              .addService(impl)
              .build()
              .start();
        servers.add(server);

        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
              .directExecutor()
              .build();
        channels.add(channel);

        return new InventoryServiceGrpcClient(InventoryServiceGrpc.newBlockingStub(channel));
    }

    @Test
    @DisplayName("successful reservation returns reservedItemIds and totalPriceCents from the response")
    void reserveInventory_success_returnsReservationResult() throws IOException {
        List<String> itemIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        String cartId = UUID.randomUUID().toString();
        AtomicReference<ReserveInventoryRequest> capturedRequest = new AtomicReference<>();

        InventoryServiceGrpcClient client = buildClient(new InventoryServiceGrpc.InventoryServiceImplBase() {
            @Override
            public void reserveInventory(ReserveInventoryRequest request,
                                         StreamObserver<ReserveInventoryResponse> responseObserver) {
                capturedRequest.set(request);
                responseObserver.onNext(ReserveInventoryResponse.newBuilder()
                      .addAllReservedItemIds(itemIds)
                      .setTotalPriceCents(2599L)
                      .build());
                responseObserver.onCompleted();
            }
        });

        InventoryServiceClient.ReservationResult result = client.reserveInventory(cartId, itemIds);

        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().getCartId()).isEqualTo(cartId);
        assertThat(capturedRequest.get().getItemIdsList()).containsExactlyElementsOf(itemIds);

        assertThat(result.reservedItemIds()).containsExactlyElementsOf(itemIds);
        assertThat(result.totalPriceCents()).isEqualTo(2599L);
    }

    @Test
    @DisplayName("FAILED_PRECONDITION maps to InsufficientStockException wrapping the original StatusRuntimeException")
    void reserveInventory_failedPrecondition_throwsInsufficientStockException() throws IOException {
        InventoryServiceGrpcClient client = buildClient(new InventoryServiceGrpc.InventoryServiceImplBase() {
            @Override
            public void reserveInventory(ReserveInventoryRequest request,
                                         StreamObserver<ReserveInventoryResponse> responseObserver) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                      .withDescription("Out of stock")
                      .asRuntimeException());
            }
        });

        String cartId = UUID.randomUUID().toString();
        List<String> itemIds = List.of(UUID.randomUUID().toString());

        assertThatThrownBy(() -> client.reserveInventory(cartId, itemIds))
              .isInstanceOf(InsufficientStockException.class)
              .hasCauseInstanceOf(StatusRuntimeException.class);
    }

    @Test
    @DisplayName("NOT_FOUND maps to ResourceNotFoundException carrying the ITEM_NOT_FOUND error code")
    void reserveInventory_notFound_throwsResourceNotFoundExceptionWithItemNotFoundCode() throws IOException {
        InventoryServiceGrpcClient client = buildClient(new InventoryServiceGrpc.InventoryServiceImplBase() {
            @Override
            public void reserveInventory(ReserveInventoryRequest request,
                                         StreamObserver<ReserveInventoryResponse> responseObserver) {
                responseObserver.onError(Status.NOT_FOUND
                      .withDescription("Item not found")
                      .asRuntimeException());
            }
        });

        String cartId = UUID.randomUUID().toString();
        List<String> itemIds = List.of(UUID.randomUUID().toString());

        assertThatThrownBy(() -> client.reserveInventory(cartId, itemIds))
              .isInstanceOf(ResourceNotFoundException.class)
              .hasCauseInstanceOf(StatusRuntimeException.class)
              .satisfies(ex -> assertThat(((ResourceNotFoundException) ex).getErrorCode())
                    .isEqualTo(OrderErrorCode.ITEM_NOT_FOUND));
    }

    @Test
    @DisplayName("INVALID_ARGUMENT maps to IllegalArgumentException using the server's description as the message")
    void reserveInventory_invalidArgument_throwsIllegalArgumentExceptionWithServerDescription() throws IOException {
        InventoryServiceGrpcClient client = buildClient(new InventoryServiceGrpc.InventoryServiceImplBase() {
            @Override
            public void reserveInventory(ReserveInventoryRequest request,
                                         StreamObserver<ReserveInventoryResponse> responseObserver) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                      .withDescription("cartId must not be blank")
                      .asRuntimeException());
            }
        });

        List<String> itemIds = List.of(UUID.randomUUID().toString());

        assertThatThrownBy(() -> client.reserveInventory("", itemIds))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessage("cartId must not be blank");
    }

    @Test
    @DisplayName("unmapped code (UNAVAILABLE) maps to DownstreamDependencyException carrying service name and grpc code")
    void reserveInventory_unavailable_throwsDownstreamDependencyException() throws IOException {
        InventoryServiceGrpcClient client = buildClient(new InventoryServiceGrpc.InventoryServiceImplBase() {
            @Override
            public void reserveInventory(ReserveInventoryRequest request,
                                         StreamObserver<ReserveInventoryResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE
                      .withDescription("inventory-service is down")
                      .asRuntimeException());
            }
        });

        String cartId = UUID.randomUUID().toString();
        List<String> itemIds = List.of(UUID.randomUUID().toString());

        assertThatThrownBy(() -> client.reserveInventory(cartId, itemIds))
              .isInstanceOf(DownstreamDependencyException.class)
              .hasCauseInstanceOf(StatusRuntimeException.class)
              .satisfies(ex -> {
                  DownstreamDependencyException dde = (DownstreamDependencyException) ex;
                  assertThat(dde.getGrpcCode()).isEqualTo(Status.Code.UNAVAILABLE);
                  assertThat(dde.getServiceName()).isEqualTo("inventory-service");
              });
    }

    @Test
    @DisplayName("client-side deadline expiry (no server response) maps to DownstreamDependencyException with DEADLINE_EXCEEDED")
    void reserveInventory_deadlineExceeded_throwsDownstreamDependencyException() throws IOException {
        InventoryServiceGrpcClient client = buildClient(new InventoryServiceGrpc.InventoryServiceImplBase() {
            @Override
            public void reserveInventory(ReserveInventoryRequest request,
                                         StreamObserver<ReserveInventoryResponse> responseObserver) {
                // Silent server to force deadline expiration
            }
        });

        String cartId = UUID.randomUUID().toString();
        List<String> itemIds = List.of(UUID.randomUUID().toString());

        assertThatThrownBy(() -> client.reserveInventory(cartId, itemIds))
              .isInstanceOf(DownstreamDependencyException.class)
              .satisfies(ex -> assertThat(((DownstreamDependencyException) ex).getGrpcCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }
}