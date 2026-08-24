package com.echcherqaoui.orderflow.inventory.grpc;

import com.echcherqaoui.orderflow.inventory.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.service.impl.ReservationServiceImpl;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class InventoryGrpcServiceIT extends AbstractIntegrationTest {

    @MockitoBean
    private ReservationServiceImpl reservationService;

    private ManagedChannel channel;
    private InventoryServiceGrpc.InventoryServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp(@Value("${local.grpc.port}") int grpcPort) {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
              .usePlaintext()
              .build();
        blockingStub = InventoryServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("reserveInventory converts UUIDs and returns reserved item IDs and total price on success")
    void reserveInventory_success() {
        String cartId = "cart-100";
        UUID itemId1 = UUID.randomUUID();
        UUID itemId2 = UUID.randomUUID();
        long totalPriceCents = 5000L;

        when(reservationService.reserve(cartId, Set.of(itemId1, itemId2)))
              .thenReturn(totalPriceCents);

        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId(cartId)
              .addAllItemIds(List.of(itemId1.toString(), itemId2.toString()))
              .build();

        ReserveInventoryResponse response = blockingStub.reserveInventory(request);

        assertThat(response.getReservedItemIdsList())
              .containsExactlyInAnyOrder(itemId1.toString(), itemId2.toString());
        assertThat(response.getTotalPriceCents()).isEqualTo(totalPriceCents);

        verify(reservationService).reserve(cartId, Set.of(itemId1, itemId2));
    }

    @Test
    @DisplayName("reserveInventory returns Status.INVALID_ARGUMENT when item ID is not a valid UUID")
    void reserveInventory_invalidUuidFormat_returnsInvalidArgument() {
        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId("cart-100")
              .addItemIds("invalid-uuid-string")
              .build();

        assertThatThrownBy(() -> blockingStub.reserveInventory(request))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(throwable -> {
                  StatusRuntimeException ex = (StatusRuntimeException) throwable;
                  assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                  assertThat(ex.getStatus().getDescription()).contains("Invalid UUID");
              });
    }

    @Test
    @DisplayName("reserveInventory maps OutOfStockException to Status.FAILED_PRECONDITION")
    void reserveInventory_outOfStockException_returnsFailedPrecondition() {
        String cartId = "cart-100";
        UUID itemId = UUID.randomUUID();
        OutOfStockException domainException = new OutOfStockException(itemId);

        doThrow(domainException)
              .when(reservationService).reserve(cartId, Set.of(itemId));

        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId(cartId)
              .addItemIds(itemId.toString())
              .build();

        assertThatThrownBy(() -> blockingStub.reserveInventory(request))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(throwable -> {
                  StatusRuntimeException ex = (StatusRuntimeException) throwable;
                  assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                  assertThat(ex.getStatus().getDescription()).isEqualTo(domainException.getMessage());
              });
    }

    @Test
    @DisplayName("reserveInventory handles empty item list successfully")
    void reserveInventory_emptyItemIds_success() {
        String cartId = "cart-100";
        when(reservationService.reserve(cartId, Set.of())).thenReturn(0L);

        ReserveInventoryRequest request = ReserveInventoryRequest.newBuilder()
              .setCartId(cartId)
              .build();

        ReserveInventoryResponse response = blockingStub.reserveInventory(request);

        assertThat(response.getReservedItemIdsList()).isEmpty();
        assertThat(response.getTotalPriceCents()).isZero();
        verify(reservationService).reserve(cartId, Set.of());
    }
}