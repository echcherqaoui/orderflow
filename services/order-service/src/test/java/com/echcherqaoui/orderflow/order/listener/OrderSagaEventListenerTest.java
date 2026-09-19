package com.echcherqaoui.orderflow.order.listener;

import com.echcherqaoui.orderflow.order.events.OrderCancelledEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentFailedEvent;
import com.echcherqaoui.orderflow.order.events.OrderPaymentSessionActiveEvent;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        @Test
        @DisplayName("null event throws NullPointerException")
        void handlePaymentFailed_nullEvent_throwsNullPointerException() {
            assertThatThrownBy(() -> listener.handlePaymentFailed(null))
                  .isInstanceOf(NullPointerException.class);
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

        @Test
        @DisplayName("null event throws NullPointerException")
        void handlePaymentSessionActive_nullEvent_throwsNullPointerException() {
            assertThatThrownBy(() -> listener.handlePaymentSessionActive(null))
                  .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("handleOrderCancelled(OrderCancelledEvent)")
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

        @Test
        @DisplayName("null event throws NullPointerException")
        void handleOrderCancelled_nullEvent_throwsNullPointerException() {
            assertThatThrownBy(() -> listener.handleOrderCancelled((OrderCancelledEvent) null))
                  .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("handleOrderCancelled(ReservationExtensionFailedOrderEvent)")
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

        @Test
        @DisplayName("null event throws NullPointerException")
        void handleReservationExtensionFailed_nullEvent_throwsNullPointerException() {
            assertThatThrownBy(() -> listener.handleOrderCancelled((ReservationExtensionFailedOrderEvent) null))
                  .isInstanceOf(NullPointerException.class);
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

        @Test
        @DisplayName("null event throws NullPointerException")
        void handleReservationExtended_nullEvent_throwsNullPointerException() {
            assertThatThrownBy(() -> listener.handleReservationExtended(null))
                  .isInstanceOf(NullPointerException.class);
        }
    }
}