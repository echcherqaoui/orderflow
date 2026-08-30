package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.client.InventoryServiceClient;
import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.model.Order;
import com.echcherqaoui.orderflow.order.model.OrderItem;
import com.echcherqaoui.orderflow.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.SUCCEEDED;

@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private final OrderRepository orderRepository;
    private final SagaStepLogger sagaStepLogger;
    private final OutboxWriter outboxWriter;

    @Transactional
    public void persistReservedOrder(UUID orderId,
                                     @NonNull CreateOrderRequest request,
                                     InventoryServiceClient.@NonNull ReservationResult reservation) {

        long totalPriceCents = reservation.totalPriceCents();
        String userId = request.userId();

        Order order = new Order()
              .setId(orderId)
              .setUserId(userId)
              .setCartId(request.cartId())
              .setCurrentSagaStep(INVENTORY_RESERVED)
              .setTotalAmountCents(totalPriceCents);

        for (String itemId: reservation.reservedItemIds())
            order.addItem(new OrderItem().setItemId(UUID.fromString(itemId)));

        orderRepository.save(order);

        sagaStepLogger.logStep(
              orderId,
              INVENTORY_RESERVED,
              SUCCEEDED,
              null,
              null
        );

        outboxWriter.publishChargePaymentCommand(orderId, userId, totalPriceCents);
    }
}