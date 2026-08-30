package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.exception.domain.CartAlreadyProcessedException;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
import com.echcherqaoui.orderflow.order.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;


/**
 * gRPC reservation happens synchronously OUTSIDE any DB transaction
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final OrderPersistenceService orderPersistenceService;
    private final SseEmitterRegistry emitterRegistry;

    public SseEmitter createOrder(@NonNull CreateOrderRequest request) {
        if (orderRepository.existsByCartId(request.cartId()))
            throw new CartAlreadyProcessedException(request.cartId());

        InventoryServiceClient.ReservationResult reservation =
                inventoryServiceClient.reserveInventory(request.cartId(), request.itemIds());

        UUID orderId = UUID.randomUUID();
        SseEmitter emitter = emitterRegistry.register(orderId);

        try {
            orderPersistenceService.persistReservedOrder(orderId, request, reservation);
            return emitter;
        } catch (Exception e) {
            emitterRegistry.remove(orderId);
            emitter.completeWithError(e);
            throw e;
        }
    }
}