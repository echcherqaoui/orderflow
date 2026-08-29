package com.echcherqaoui.orderflow.order.client.impl;

import com.echcherqaoui.orderflow.exception.grpc.DownstreamDependencyException;
import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryRequest;
import com.echcherqaoui.orderflow.inventory.grpc.ReserveInventoryResponse;
import com.echcherqaoui.orderflow.order.client.InventoryServiceClient.ReservationResult;
import com.echcherqaoui.orderflow.order.exception.domain.InsufficientStockException;
import com.echcherqaoui.orderflow.order.exception.domain.ResourceNotFoundException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import java.util.concurrent.TimeUnit;

import static com.echcherqaoui.orderflow.order.exception.code.OrderErrorCode.ITEM_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class InventoryServiceGrpcClientTest {

    @Mock
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @InjectMocks
    private InventoryServiceGrpcClient inventoryServiceClient;

    @Captor
    private ArgumentCaptor<ReserveInventoryRequest> requestCaptor;

    private final String cartId = "cart-123";
    private final List<String> itemIds = List.of("item-1", "item-2");

    @BeforeEach
    void setUp() {
        given(inventoryStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).willReturn(inventoryStub);
    }

    @Nested
    @DisplayName("reserveInventory()")
    class ReserveInventory {

        @Test
        @DisplayName("successful gRPC invocation maps response to ReservationResult and sets 3s deadline")
        @SuppressWarnings("ResultOfMethodCallIgnored")
        void reserveInventory_success_returnsReservationResult() {
            ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                  .addAllReservedItemIds(itemIds)
                  .setTotalPriceCents(4500L)
                  .build();

            given(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class))).willReturn(response);

            ReservationResult result = inventoryServiceClient.reserveInventory(cartId, itemIds);

            assertThat(result).isNotNull();
            assertThat(result.reservedItemIds()).containsExactlyElementsOf(itemIds);
            assertThat(result.totalPriceCents()).isEqualTo(4500L);

            // Verification calls trigger @CheckReturnValue warnings without @SuppressWarnings
            then(inventoryStub).should().withDeadlineAfter(3L, TimeUnit.SECONDS);
            then(inventoryStub).should().reserveInventory(requestCaptor.capture());
        }

        @Test
        @DisplayName("gRPC status FAILED_PRECONDITION maps to InsufficientStockException")
        void reserveInventory_failedPrecondition_throwsInsufficientStockException() {
            StatusRuntimeException exception = Status.FAILED_PRECONDITION
                  .withDescription("Insufficient inventory level for item")
                  .asRuntimeException();

            given(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class))).willThrow(exception);

            assertThatThrownBy(() -> inventoryServiceClient.reserveInventory(cartId, itemIds))
                  .isInstanceOf(InsufficientStockException.class)
                  .hasCause(exception);
        }

        @Test
        @DisplayName("gRPC status NOT_FOUND maps to ResourceNotFoundException with ITEM_NOT_FOUND error code")
        void reserveInventory_notFound_throwsResourceNotFoundException() {
            StatusRuntimeException exception = Status.NOT_FOUND
                  .withDescription("Item ID not found")
                  .asRuntimeException();

            given(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class))).willThrow(exception);

            assertThatThrownBy(() -> inventoryServiceClient.reserveInventory(cartId, itemIds))
                  .isInstanceOf(ResourceNotFoundException.class)
                  .hasCause(exception)
                  .extracting("errorCode")
                  .isEqualTo(ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("gRPC status INVALID_ARGUMENT maps to IllegalArgumentException with status description")
        void reserveInventory_invalidArgument_throwsIllegalArgumentException() {
            String errorDescription = "cartId must not be blank";
            StatusRuntimeException exception = Status.INVALID_ARGUMENT
                  .withDescription(errorDescription)
                  .asRuntimeException();

            given(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class))).willThrow(exception);

            assertThatThrownBy(() -> inventoryServiceClient.reserveInventory(cartId, itemIds))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage(errorDescription)
                  .hasCause(exception);
        }

        @Test
        @DisplayName("unhandled gRPC status maps to DownstreamDependencyException with service metadata")
        void reserveInventory_unhandledGrpcStatus_throwsDownstreamDependencyException() {
            String errorDescription = "Connection timeout reaching inventory server";
            StatusRuntimeException exception = Status.UNAVAILABLE
                  .withDescription(errorDescription)
                  .asRuntimeException();

            given(inventoryStub.reserveInventory(any(ReserveInventoryRequest.class))).willThrow(exception);

            assertThatThrownBy(() -> inventoryServiceClient.reserveInventory(cartId, itemIds))
                  .isInstanceOf(DownstreamDependencyException.class)
                  .hasCause(exception)
                  .satisfies(ex -> {
                      DownstreamDependencyException downstreamEx = (DownstreamDependencyException) ex;
                      assertThat(downstreamEx.getServiceName()).isEqualTo("inventory-service");
                      assertThat(downstreamEx.getGrpcCode()).isEqualTo(Status.Code.UNAVAILABLE);
                  });
        }
    }
}