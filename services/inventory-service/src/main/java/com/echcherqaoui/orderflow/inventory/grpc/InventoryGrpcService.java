package com.echcherqaoui.orderflow.inventory.grpc;

import com.echcherqaoui.orderflow.inventory.exception.domain.OutOfStockException;
import com.echcherqaoui.orderflow.inventory.service.impl.ReservationServiceImpl;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.grpc.Status.FAILED_PRECONDITION;
import static io.grpc.Status.INVALID_ARGUMENT;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final ReservationServiceImpl reservationService;

    @Override
    public void reserveInventory(@NonNull ReserveInventoryRequest request,
                                 @NonNull StreamObserver<ReserveInventoryResponse> responseObserver) {
        try {
            Set<UUID> itemIds = request.getItemIdsList().stream()
                  .map(UUID::fromString)
                  .collect(Collectors.toSet());

            //  Execute stock decrement & reservation persistence
            long totalPriceCents = reservationService.reserve(request.getCartId(), itemIds);

            ReserveInventoryResponse response = ReserveInventoryResponse.newBuilder()
                  .addAllReservedItemIds(itemIds.stream().map(UUID::toString).toList())
                  .setTotalPriceCents(totalPriceCents)
                  .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                  INVALID_ARGUMENT
                        .withDescription("Invalid UUID string format in requested item IDs")
                        .asRuntimeException()
            );
        } catch (OutOfStockException e) {
            responseObserver.onError(
                  FAILED_PRECONDITION
                        .withDescription(e.getMessage())
                        .asRuntimeException()
            );
        }
    }
}