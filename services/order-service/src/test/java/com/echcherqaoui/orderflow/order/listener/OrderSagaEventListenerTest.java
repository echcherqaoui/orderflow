package com.echcherqaoui.orderflow.order.listener;

import com.echcherqaoui.orderflow.order.events.OrderCancelledEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentFailedEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentSessionActiveEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtendedOrderEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtensionFailedOrderEvent;
import com.echcherqaoui.orderflow.order.sse.SseEmitterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderSagaEventListenerTest {

    @Mock
    private SseEmitterRegistry emitterRegistry;

    @InjectMocks
    private OrderSagaEventListener listener;

    private final UUID orderId = UUID.randomUUID();

    @Test
    @DisplayName("handlePaymentFailed sends PAYMENT_FAILED and keeps emitter open")
    void handlePaymentFailed_sendsPaymentFailedStatus() {
        OrderPaymentFailedEvent event = new OrderPaymentFailedEvent(orderId, "CARD_DECLINED");

        listener.handlePaymentFailed(event);

        then(emitterRegistry).should().sendAndKeepOpen(
              orderId,
              Map.of(
                    "status", "PAYMENT_FAILED",
                    "reason", "CARD_DECLINED"
              )
        );
    }

    @Test
    @DisplayName("handlePaymentSessionActive sends PAYMENT_READY and keeps emitter open")
    void handlePaymentSessionActive_sendsPaymentReadyStatus() {
        OrderPaymentSessionActiveEvent event = new OrderPaymentSessionActiveEvent(orderId, "pi_123456");

        listener.handlePaymentSessionActive(event);

        then(emitterRegistry).should().sendAndKeepOpen(
              orderId,
              Map.of(
                    "status", "PAYMENT_READY",
                    "paymentIntentId", "pi_123456"
              )
        );
    }

    @Test
    @DisplayName("handleOrderCancelled sends ORDER_CANCELLED and completes emitter")
    void handleOrderCancelled_sendsOrderCancelledStatusAndCompletes() {
        OrderCancelledEvent event = new OrderCancelledEvent(orderId, "TIMEOUT");

        listener.handleOrderCancelled(event);

        then(emitterRegistry).should().sendAndComplete(
              orderId,
              Map.of(
                    "status", "ORDER_CANCELLED",
                    "reason", "TIMEOUT"
              )
        );
    }

    @Test
    @DisplayName("handleReservationExtensionFailed sends ORDER_CANCELLED and keeps emitter open")
    void handleReservationExtensionFailed_sendsOrderCancelledStatus() {
        ReservationExtensionFailedOrderEvent event = new ReservationExtensionFailedOrderEvent(orderId, "STOCK_EXHAUSTED");

        listener.handleOrderCancelled(event);

        then(emitterRegistry).should().sendAndKeepOpen(
              orderId,
              Map.of(
                    "status", "ORDER_CANCELLED",
                    "reason", "STOCK_EXHAUSTED"
              )
        );
    }

    @Test
    @DisplayName("handleReservationExtended sends PAYMENT_SESSION_ACTIVE with epoch seconds")
    void handleReservationExtended_sendsPaymentSessionActiveStatus() {
        Instant expiresAt = Instant.ofEpochSecond(1700000000L);
        ReservationExtendedOrderEvent event = new ReservationExtendedOrderEvent(orderId, expiresAt);

        listener.handleReservationExtended(event);

        then(emitterRegistry).should().sendAndKeepOpen(
              orderId,
              Map.of(
                    "status", "PAYMENT_SESSION_ACTIVE",
                    "expiresAt", 1700000000L
              )
        );
    }
}