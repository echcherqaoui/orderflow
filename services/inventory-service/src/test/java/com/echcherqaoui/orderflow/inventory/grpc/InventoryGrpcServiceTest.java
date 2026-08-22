package com.echcherqaoui.orderflow.inventory.grpc;

import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.service.impl.ReservationServiceImpl;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class InventoryGrpcServiceTest {

    @Mock
    private ReservationServiceImpl reservationService;

    @Mock
    private StreamObserver<ReserveInventoryResponse> responseObserver;

    @InjectMocks
    private InventoryGrpcService inventoryGrpcService;

    @Captor
    private ArgumentCaptor<ReserveInventoryResponse> responseCaptor;

    @Captor
    private ArgumentCaptor<Throwable> errorCaptor;

    @Test
    @DisplayName("reserveInventory succeeds and emits response when request is valid")
    void reserveInventory_success() {
        String cartId = "cart-123";
        UUID itemId1 = UUID.randomUUID();
        UUID itemId2 = UUID.randomUUID();

        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId(cartId)
              .addAllItemIds(List.of(itemId1.toString(), itemId2.toString()))
              .build();

        inventoryGrpcService.reserveInventory(request, responseObserver);

        verify(reservationService).reserve(cartId, Set.of(itemId1, itemId2));
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        ReserveInventoryResponse response = responseCaptor.getValue();
        assertThat(response.getReservedItemIdsList())
              .containsExactlyInAnyOrder(itemId1.toString(), itemId2.toString());
    }

    @Test
    @DisplayName("reserveInventory returns INVALID_ARGUMENT status when UUID format is invalid")
    void reserveInventory_invalidUuid_returnsInvalidArgument() {
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId("cart-123")
              .addItemIds("not-a-valid-uuid")
              .build();

        inventoryGrpcService.reserveInventory(request, responseObserver);

        verifyNoInteractions(reservationService);
        verify(responseObserver).onError(errorCaptor.capture());

        StatusRuntimeException exception = (StatusRuntimeException) errorCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(exception.getStatus().getDescription()).contains("Invalid UUID");
    }

    @Test
    @DisplayName("reserveInventory returns FAILED_PRECONDITION status and propagates domain message on OutOfStockException")
    void reserveInventory_outOfStock_returnsFailedPrecondition() {
        String cartId = "cart-123";
        UUID itemId = UUID.randomUUID();
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId(cartId)
              .addItemIds(itemId.toString())
              .build();

        OutOfStockException domainException = new OutOfStockException(itemId);
        doThrow(domainException)
              .when(reservationService).reserve(cartId, Set.of(itemId));

        inventoryGrpcService.reserveInventory(request, responseObserver);

        verify(responseObserver).onError(errorCaptor.capture());

        StatusRuntimeException exception = (StatusRuntimeException) errorCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(exception.getStatus().getDescription()).isEqualTo(domainException.getMessage());
    }

    @Test
    @DisplayName("reserveInventory handles empty items list successfully")
    void reserveInventory_emptyItems_success() {
        String cartId = "cart-123";
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId(cartId)
              .build();

        inventoryGrpcService.reserveInventory(request, responseObserver);

        verify(reservationService).reserve(cartId, Set.of());
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        assertThat(responseCaptor.getValue().getReservedItemIdsList()).isEmpty();
    }
}