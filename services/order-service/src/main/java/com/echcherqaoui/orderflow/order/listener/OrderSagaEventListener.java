package com.echcherqaoui.orderflow.order.listener;

import com.echcherqaoui.orderflow.order.events.OrderCancelledEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentFailedEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentSessionActiveEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtendedOrderEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtensionFailedOrderEvent;
import com.echcherqaoui.orderflow.order.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Component
@RequiredArgsConstructor
public class OrderSagaEventListener {

    private final SseEmitterRegistry emitterRegistry;

    @Async("taskExecutor")
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void handlePaymentFailed(@lombok.NonNull OrderPaymentFailedEvent event) {
        emitterRegistry.sendAndComplete(
              event.orderId(),
              Map.of(
                    "status", "PAYMENT_FAILED",
                    "reason", event.reason()
              )
        );
    }

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentSessionActive(@lombok.NonNull OrderPaymentSessionActiveEvent event) {
        emitterRegistry.sendAndKeepOpen(
              event.orderId(),
              Map.of(
                    "status", "PAYMENT_READY",
                    "paymentIntentId", event.paymentIntentId()
              )
        );
    }

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCancelled(@NonNull OrderCancelledEvent event) {
        emitterRegistry.sendAndComplete(
              event.orderId(),
              Map.of(
                    "status", "ORDER_CANCELLED",
                    "reason", event.reason()
              )
        );
    }

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCancelled(@NonNull ReservationExtensionFailedOrderEvent event) {
        emitterRegistry.sendAndComplete(
              event.orderId(),
              Map.of(
                    "status", "ORDER_CANCELLED",
                    "reason", event.reason()
              )
        );
    }

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleReservationExtended(@NonNull ReservationExtendedOrderEvent event) {
        emitterRegistry.sendAndKeepOpen(
              event.orderId(),
              Map.of(
                    "status", "PAYMENT_SESSION_ACTIVE",
                    "expiresAt", event.newExpiresAt().getEpochSecond()
              )
        );
    }
}