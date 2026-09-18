package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.PaymentError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {

    @Mock
    private WebhookIdempotencyService idempotencyService;

    @Mock
    private PaymentPersistenceService paymentPersistenceService;

    @InjectMocks
    private StripeWebhookService stripeWebhookService;

    private final String eventId = "evt_stripe_123456";
    private final String paymentIntentId = "pi_stripe_987654";

    @Nested
    @DisplayName("processWebhook()")
    class ProcessWebhook {

        @Test
        @DisplayName("null payload throws NullPointerException")
        void nullPayload_throwsNullPointerException() {
            assertThatThrownBy(() -> stripeWebhookService.processWebhook(null))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessageContaining("payload");
        }

        @Test
        @DisplayName("duplicate event triggers DataIntegrityViolationException and exits cleanly")
        void duplicateEvent_catchesDataIntegrityViolationExceptionAndExitsCleanly() {
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", null);

            willThrow(new DataIntegrityViolationException("Duplicate event key"))
                  .given(idempotencyService)
                  .registerEvent(eventId, "payment_intent.succeeded");

            assertThatNoException().isThrownBy(() -> stripeWebhookService.processWebhook(payload));

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.succeeded");
            then(paymentPersistenceService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("payment_intent.succeeded event registers idempotency and marks payment charged with outbox")
        void paymentIntentSucceeded_registersEventAndMarksChargedWithOutbox() {
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "succeeded", null);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", data);

            stripeWebhookService.processWebhook(payload);

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.succeeded");
            then(paymentPersistenceService).should().markPaymentChargedAndOutbox(paymentIntentId);
        }

        @Test
        @DisplayName("payment_intent.payment_failed event with lastPaymentError message marks payment failed")
        void paymentIntentFailed_withLastPaymentError_marksPaymentFailedWithReason() {
            PaymentError error = new PaymentError("card_declined", "Insufficient funds");
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "failed", error);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", data);

            stripeWebhookService.processWebhook(payload);

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.payment_failed");
            then(paymentPersistenceService).should().markPaymentFailedAndOutbox(paymentIntentId, "Insufficient funds");
        }

        @Test
        @DisplayName("payment_intent.payment_failed event with null lastPaymentError falls back to default reason")
        void paymentIntentFailed_withNullLastPaymentError_marksPaymentFailedWithDefaultReason() {
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "failed", null);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", data);

            stripeWebhookService.processWebhook(payload);

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.payment_failed");
            then(paymentPersistenceService).should().markPaymentFailedAndOutbox(paymentIntentId, "Payment authorization failed");
        }

        @Test
        @DisplayName("unhandled event type registers idempotency and logs warning without persisting")
        void unhandledEventType_registersEventAndExitsWithoutPersistence() {
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "active", null);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "customer.created", data);

            stripeWebhookService.processWebhook(payload);

            then(idempotencyService).should().registerEvent(eventId, "customer.created");
            then(paymentPersistenceService).shouldHaveNoInteractions();
        }
    }
}