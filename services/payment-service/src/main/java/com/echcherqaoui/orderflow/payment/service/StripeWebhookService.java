package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.exception.domain.InvalidWebhookSignatureException;
import com.echcherqaoui.orderflow.payment.model.StripeEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.echcherqaoui.orderflow.payment.exception.code.PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE;


@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final WebhookIdempotencyService idempotencyService;
    private final PaymentPersistenceService paymentPersistenceService;
    private final StripeWebhookSignatureVerifier webhookSignatureVerifier;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private static class DuplicateEventException extends RuntimeException {
        DuplicateEventException(Throwable cause) {
            super(cause);
        }
    }

    private void verifySignature(String signature, String rawPayload) {
        if (!webhookSignatureVerifier.verify(rawPayload, signature)) {
            log.error("Invalid Stripe webhook signature");
            throw new InvalidWebhookSignatureException(INVALID_WEBHOOK_SIGNATURE);
        }
    }

    private StripeWebhookPayload parse(String rawPayload) {
        try {
            return objectMapper.readValue(rawPayload, StripeWebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse Stripe webhook payload", e);
            throw new IllegalArgumentException("Invalid payload format", e);
        }
    }

    private void registerOrThrowDuplicate(StripeWebhookPayload payload) {
        try {
            idempotencyService.registerEvent(payload.id(), payload.type());
        } catch (DataIntegrityViolationException e) {
            // Isolate deduplication table constraint failures from downstream payment dispatch errors
            throw new DuplicateEventException(e);
        }
    }

    private void dispatch(@lombok.NonNull StripeWebhookPayload payload) {
        String eventType = payload.type();
        String paymentIntentId = payload.data().object().id();
        StripeWebhookPayload.PaymentError lastError = payload.data().object().lastPaymentError();
        String errorCode = lastError != null ? lastError.code() : "unknown_error";
        String errorMessage = lastError != null ? lastError.message() : "Payment authorization failed";


        StripeEventType.from(eventType).ifPresentOrElse(
              type -> {
                  switch (type) {
                      case PAYMENT_INTENT_SUCCEEDED ->
                            paymentPersistenceService.recordSuccessAndOutbox(paymentIntentId);
                      case PAYMENT_INTENT_PAYMENT_FAILED ->
                            paymentPersistenceService.recordFailedAttempt(paymentIntentId, errorCode, errorMessage);
                      case PAYMENT_INTENT_CANCELED ->
                            paymentPersistenceService.recordCanceledAndOutbox(paymentIntentId, errorCode, errorMessage);
                      default ->
                            log.warn("Unhandled webhook event type: [{}]", eventType);
                  }
              },
              () -> log.warn("Unrecognized webhook event type: [{}]", eventType)
        );
    }

    // TransactionTemplate manages boundaries explicitly
    public void processWebhook(@NonNull String signature, @NonNull String rawPayload) {
        // Validate signature and parse JSON payload outside DB transaction boundary
        verifySignature(signature, rawPayload);
        StripeWebhookPayload payload = parse(rawPayload);

        try {
            // Execute idempotency persistence, payment processing, and outbox writes atomically
            transactionTemplate.executeWithoutResult(status -> {
                registerOrThrowDuplicate(payload);
                dispatch(payload);
            });
        } catch (DuplicateEventException e) {
            // Transaction rolled back by TransactionTemplate; method completes normally to yield 200 OK
            log.info("Webhook event [{}] of type [{}] already processed. Skipping.", payload.id(), payload.type());
        }
    }
}