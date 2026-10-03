package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.PaymentError;
import com.echcherqaoui.orderflow.payment.exception.domain.InvalidWebhookSignatureException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {

    @Mock
    private WebhookIdempotencyService idempotencyService;

    @Mock
    private PaymentPersistenceService paymentPersistenceService;

    @Mock
    private StripeWebhookSignatureVerifier webhookSignatureVerifier;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private StripeWebhookService stripeWebhookService;

    private final String signature = "t=12345,v1=signature_hash";
    private final String rawPayload = "{\"id\":\"evt_123\"}";
    private final String eventId = "evt_stripe_123456";
    private final String paymentIntentId = "pi_stripe_987654";

    @BeforeEach
    void setUpTransactionTemplateMock() {
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Nested
    @DisplayName("processWebhook()")
    class ProcessWebhook {

        @Test
        @DisplayName("null signature throws NullPointerException")
        void nullSignature_throwsNullPointerException() {
            assertThatThrownBy(() -> stripeWebhookService.processWebhook(null, rawPayload))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessageContaining("signature");
        }

        @Test
        @DisplayName("null rawPayload throws NullPointerException")
        void nullRawPayload_throwsNullPointerException() {
            assertThatThrownBy(() -> stripeWebhookService.processWebhook(signature, null))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessageContaining("rawPayload");
        }

        @Test
        @DisplayName("invalid webhook signature throws InvalidWebhookSignatureException")
        void invalidSignature_throwsInvalidWebhookSignatureException() {
            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(false);

            assertThatThrownBy(() -> stripeWebhookService.processWebhook(signature, rawPayload))
                  .isInstanceOf(InvalidWebhookSignatureException.class);

            then(objectMapper).shouldHaveNoInteractions();
            then(transactionTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("malformed JSON rawPayload throws IllegalArgumentException")
        void malformedJson_throwsIllegalArgumentException() throws Exception {
            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(true);
            given(objectMapper.readValue(rawPayload, StripeWebhookPayload.class))
                  .willThrow(new JsonProcessingException("Invalid JSON") {});

            assertThatThrownBy(() -> stripeWebhookService.processWebhook(signature, rawPayload))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Invalid payload format");

            then(transactionTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("duplicate event triggers DataIntegrityViolationException and exits cleanly")
        void duplicateEvent_catchesDataIntegrityViolationExceptionAndExitsCleanly() throws Exception {
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", null);

            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(true);
            given(objectMapper.readValue(rawPayload, StripeWebhookPayload.class)).willReturn(payload);
            willThrow(new DataIntegrityViolationException("Duplicate event key"))
                  .given(idempotencyService)
                  .registerEvent(eventId, "payment_intent.succeeded");

            assertThatNoException().isThrownBy(() -> stripeWebhookService.processWebhook(signature, rawPayload));

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.succeeded");
            then(paymentPersistenceService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("payment_intent.succeeded event registers idempotency and records success with outbox")
        void paymentIntentSucceeded_registersEventAndRecordsSuccessWithOutbox() throws Exception {
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "succeeded", null);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", data);

            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(true);
            given(objectMapper.readValue(rawPayload, StripeWebhookPayload.class)).willReturn(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.succeeded");
            then(paymentPersistenceService).should().recordSuccessAndOutbox(paymentIntentId);
        }

        @Test
        @DisplayName("payment_intent.payment_failed event with lastPaymentError records failed attempt with details")
        void paymentIntentFailed_withLastPaymentError_recordsFailedAttemptWithDetails() throws Exception {
            PaymentError error = new PaymentError("card_declined", "Insufficient funds");
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "failed", error);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", data);

            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(true);
            given(objectMapper.readValue(rawPayload, StripeWebhookPayload.class)).willReturn(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.payment_failed");
            then(paymentPersistenceService).should().recordFailedAttempt(paymentIntentId, "card_declined", "Insufficient funds");
        }

        @Test
        @DisplayName("payment_intent.payment_failed event with null lastPaymentError falls back to default error details")
        void paymentIntentFailed_withNullLastPaymentError_recordsFailedAttemptWithDefaults() throws Exception {
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "failed", null);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", data);

            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(true);
            given(objectMapper.readValue(rawPayload, StripeWebhookPayload.class)).willReturn(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.payment_failed");
            then(paymentPersistenceService).should().recordFailedAttempt(paymentIntentId, "unknown_error", "Payment authorization failed");
        }

        @Test
        @DisplayName("payment_intent.canceled event records canceled status with outbox")
        void paymentIntentCanceled_recordsCanceledAndOutbox() throws Exception {
            PaymentError error = new PaymentError("abandoned", "Customer canceled payment");
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "canceled", error);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.canceled", data);

            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(true);
            given(objectMapper.readValue(rawPayload, StripeWebhookPayload.class)).willReturn(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            then(idempotencyService).should().registerEvent(eventId, "payment_intent.canceled");
            then(paymentPersistenceService).should().recordCanceledAndOutbox(paymentIntentId, "abandoned", "Customer canceled payment");
        }

        @Test
        @DisplayName("unhandled or unrecognized event type registers idempotency without triggering persistence")
        void unhandledEventType_registersEventAndExitsWithoutPersistence() throws Exception {
            ObjectData objectData = new ObjectData(paymentIntentId, 12500L, "usd", "active", null);
            Data data = new Data(objectData);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "customer.created", data);

            given(webhookSignatureVerifier.verify(rawPayload, signature)).willReturn(true);
            given(objectMapper.readValue(rawPayload, StripeWebhookPayload.class)).willReturn(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            then(idempotencyService).should().registerEvent(eventId, "customer.created");
            then(paymentPersistenceService).shouldHaveNoInteractions();
        }
    }
}