package com.echcherqaoui.orderflow.order.listener;

import com.echcherqaoui.orderflow.order.events.InventoryConfirmationFailedOrderEvent;
import com.echcherqaoui.orderflow.order.events.OrderCancelledEvent;
import com.echcherqaoui.orderflow.order.events.OrderConfirmedEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentFailedEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentSessionActiveEvent;
import com.echcherqaoui.orderflow.order.events.PaymentRefundFailedOrderEvent;
import com.echcherqaoui.orderflow.order.events.PaymentRefundedOrderEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtendedOrderEvent;
import com.echcherqaoui.orderflow.order.events.ReservationExtensionFailedOrderEvent;
import com.echcherqaoui.orderflow.order.sse.SseEmitterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Nested
    @DisplayName("handlePaymentFailed()")
    class HandlePaymentFailed {

        @Test
        @DisplayName("sends PAYMENT_FAILED status and completes emitter")
        void handlePaymentFailed_sendsPaymentFailedStatusAndCompletes() {
            OrderPaymentFailedEvent event = new OrderPaymentFailedEvent(orderId, "CARD_DECLINED");

            listener.handlePaymentFailed(event);

            then(emitterRegistry).should().sendAndComplete(
                  orderId,
                  Map.of(
                        "status", "PAYMENT_FAILED",
                        "reason", "CARD_DECLINED"
                  )
            );
        }
    }

    @Nested
    @DisplayName("handlePaymentSessionActive()")
    class HandlePaymentSessionActive {

        @Test
        @DisplayName("sends PAYMENT_READY status and keeps emitter open")
        void handlePaymentSessionActive_sendsPaymentReadyStatusAndKeepsOpen() {
            OrderPaymentSessionActiveEvent event = new OrderPaymentSessionActiveEvent(orderId, "pi_123456", "secret_mock_123456");

            listener.handlePaymentSessionActive(event);

            then(emitterRegistry).should().sendAndKeepOpen(
                  orderId,
                  Map.of(
                        "status", "PAYMENT_READY",
                        "paymentIntentId", "pi_123456",
                        "clientSecret", "secret_mock_123456"
                  )
            );
        }
    }

    @Nested
    @DisplayName("handleOrderCancelled()")
    class HandleOrderCancelledEvent {

        @Test
        @DisplayName("sends ORDER_CANCELLED status and completes emitter")
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
    }

    @Nested
    @DisplayName("handleOrderCancelled()")
    class HandleReservationExtensionFailedEvent {

        @Test
        @DisplayName("sends ORDER_CANCELLED status and completes emitter")
        void handleReservationExtensionFailed_sendsOrderCancelledStatusAndCompletes() {
            ReservationExtensionFailedOrderEvent event = new ReservationExtensionFailedOrderEvent(orderId, "STOCK_EXHAUSTED");

            listener.handleOrderCancelled(event);

            then(emitterRegistry).should().sendAndComplete(
                  orderId,
                  Map.of(
                        "status", "ORDER_CANCELLED",
                        "reason", "STOCK_EXHAUSTED"
                  )
            );
        }
    }

    @Nested
    @DisplayName("handleReservationExtended()")
    class HandleReservationExtended {

        @Test
        @DisplayName("sends PAYMENT_SESSION_ACTIVE status and keeps emitter open")
        void handleReservationExtended_sendsPaymentSessionActiveStatusAndKeepsOpen() {
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

    @Nested
    @DisplayName("handleInventoryConfirmationFailed()")
    class HandleInventoryConfirmationFailed {

        @Test
        @DisplayName("sends ORDER_FAILED_OUT_OF_STOCK_REFUNDING status and keeps emitter open")
        void handleInventoryConfirmationFailed_sendsOrderFailedStatusAndKeepsOpen() {
            InventoryConfirmationFailedOrderEvent event = new InventoryConfirmationFailedOrderEvent(orderId, "OUT_OF_STOCK");

            listener.handleInventoryConfirmationFailed(event);

            then(emitterRegistry).should().sendAndKeepOpen(
                  orderId,
                  Map.of(
                        "status", "ORDER_FAILED_OUT_OF_STOCK_REFUNDING",
                        "reason", "OUT_OF_STOCK"
                  )
            );
        }
    }

    @Nested
    @DisplayName("handlePaymentRefunded()")
    class HandlePaymentRefunded {

        @Test
        @DisplayName("sends ORDER_FAILED_OUT_OF_STOCK_REFUNDED status and completes emitter")
        void handlePaymentRefunded_sendsStatusAndCompletes() {
            PaymentRefundedOrderEvent event = new PaymentRefundedOrderEvent(orderId, "RESERVATION_EXPIRED");

            listener.handlePaymentRefunded(event);

            then(emitterRegistry).should().sendAndComplete(
                  orderId,
                  Map.of(
                        "status", "ORDER_FAILED_OUT_OF_STOCK_REFUNDED",
                        "reason", "RESERVATION_EXPIRED"
                  )
            );
        }
    }

    @Nested
    @DisplayName("handlePaymentRefundFailed()")
    class OnPaymentRefundFailed {

        @Test
        @DisplayName("sends ORDER_FAILED_REFUND_FAILED status and completes emitter")
        void onPaymentRefundFailed_sendsStatusAndCompletes() {
            PaymentRefundFailedOrderEvent event = new PaymentRefundFailedOrderEvent(orderId, "GATEWAY_TIMEOUT");

            listener.handlePaymentRefundFailed(event);

            then(emitterRegistry).should().sendAndComplete(
                  orderId,
                  Map.of(
                        "status", "ORDER_FAILED_REFUND_FAILED",
                        "message", "Your refund could not be processed automatically. Our support team has been notified."
                  )
            );
        }
    }

    @Nested
    @DisplayName("handleOrderConfirmed()")
    class OnOrderConfirmed {

        @Test
        @DisplayName("sends ORDER_CONFIRMED status and completes emitter")
        void onOrderConfirmed_sendsStatusAndCompletes() {
            OrderConfirmedEvent event = new OrderConfirmedEvent(orderId);

            listener.handleOrderConfirmed(event);

            then(emitterRegistry).should().sendAndComplete(
                  orderId,
                  Map.of(
                        "status", "ORDER_CONFIRMED",
                        "message", "Your order has been successfully confirmed."
                  )
            );
        }
    }
}