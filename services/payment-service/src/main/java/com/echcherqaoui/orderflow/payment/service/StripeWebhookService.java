package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.model.StripeEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final WebhookIdempotencyService idempotencyService;
    private final PaymentPersistenceService paymentPersistenceService;

    public void processWebhook(@NonNull StripeWebhookPayload payload) {
        String eventId = payload.id();
        String eventType = payload.type();

        try {
            // Atomic lock attempt inside isolated mini-transaction
            idempotencyService.registerEvent(eventId,eventType);
        } catch (DataIntegrityViolationException e) {
            log.info("Webhook event [{}] already processed or currently processing. Skipping.", eventId);
            return; // CLEAN! Exits and returns 200 OK cleanly!
        }

        String paymentIntentId = payload.data().object().id();

        StripeEventType.from(eventType).ifPresentOrElse(
              type -> {
                  if (type == StripeEventType.PAYMENT_INTENT_SUCCEEDED)
                      paymentPersistenceService.markPaymentChargedAndOutbox(paymentIntentId);
                  else if (type == StripeEventType.PAYMENT_INTENT_PAYMENT_FAILED) {
                      String failureReason = payload.data().object().lastPaymentError() != null
                            ? payload.data().object().lastPaymentError().message()
                            : "Payment authorization failed";

                      paymentPersistenceService.markPaymentFailedAndOutbox(paymentIntentId, failureReason);
                  }
              },
              () -> log.warn("Unhandled webhook event type: [{}]", eventType)
        );
    }
}