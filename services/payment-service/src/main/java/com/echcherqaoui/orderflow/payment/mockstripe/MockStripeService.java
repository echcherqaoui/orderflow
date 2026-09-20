package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentRequest;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.PaymentError;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.SUCCEEDED;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockStripeService {

    private final MockPaymentIntentStore intentStore;
    private final RestClient restClient;
    private final Executor webhookDeliveryExecutor;
    private final MockPspProperties mockPspProperties;

    private static final int MAX_WEBHOOK_ATTEMPTS = 3;

    private boolean decideOutcome(String paymentMethod) {
        if ("pm_card_chargeDeclined".equals(paymentMethod) || "pm_card_insufficientFunds".equals(paymentMethod))
            return false;

        return ThreadLocalRandom.current().nextDouble() >= mockPspProperties.getFailureRate();
    }

    @lombok.NonNull
    private PaymentError resolvePaymentError(String paymentMethod) {
        if ("pm_card_insufficientFunds".equals(paymentMethod))
            return new PaymentError("insufficient_funds", "Your card has insufficient funds.");

        return new PaymentError("card_declined", "Your card was declined.");
    }

    private void postWebhookWithRetry(MockStripeWebhookPayload payload, String paymentIntentId, int attempt) {
        try {
            restClient.post()
                  .uri(mockPspProperties.getWebhookUrl())
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(payload)
                  .retrieve()
                  .toBodilessEntity();
        } catch (Exception e) {
            log.warn(
                  "Webhook delivery attempt {}/{} failed for intent [{}]: {}",
                  attempt,
                  MAX_WEBHOOK_ATTEMPTS,
                  paymentIntentId,
                  e.getMessage()
            );

            if (attempt >= MAX_WEBHOOK_ATTEMPTS) {
                log.error(
                      "Webhook delivery permanently failed for intent [{}] after {} attempts",
                      paymentIntentId,
                      MAX_WEBHOOK_ATTEMPTS,
                      e
                );
                return;
            }
            try {
                Thread.sleep(200L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            postWebhookWithRetry(payload, paymentIntentId, attempt + 1);
        }
    }

    public ConfirmPaymentIntentResponse confirmAndTriggerWebhook(String paymentIntentId,
                                                                 @lombok.NonNull ConfirmPaymentIntentRequest request) {
        boolean isSuccess = decideOutcome(request.paymentMethod());
        MockPaymentIntentStatus newStatus = isSuccess ? SUCCEEDED : REQUIRES_PAYMENT_METHOD;

        TransitionResult result = intentStore.transitionIfPending(
              paymentIntentId,
              current -> {
                  if (!current.clientSecret().equals(request.clientSecret()))
                      throw new IllegalArgumentException("Invalid client_secret provided for payment_intent: " + paymentIntentId);

                  return new MockPaymentIntent(
                        current.paymentIntentId(),
                        current.clientSecret(),
                        current.totalAmountCents(),
                        newStatus
                  );
              }
        );

        MockPaymentIntent intent = result.intent();

        if (intent == null)
            throw new IllegalArgumentException("No such payment_intent: " + paymentIntentId);

        // Use the exact lower_snake_case String for the wire payload
        String stripeStatus = newStatus.getValue();

        if (!result.applied())
            return new ConfirmPaymentIntentResponse(
                  paymentIntentId,
                  intent.status().getValue(),
                  intent.clientSecret()
            );

        PaymentError error = isSuccess ? null : resolvePaymentError(request.paymentMethod());
        String eventType = isSuccess ? "payment_intent.succeeded" : "payment_intent.payment_failed";

        ObjectData objectData = new ObjectData(
              paymentIntentId,
              intent.totalAmountCents(),
              "mad",
              stripeStatus, // "succeeded", "requires_payment_method" or "canceled"
              error
        );

        // Deterministic event ID generation for idempotent retries
        String eventId = "evt_mock_" + UUID.nameUUIDFromBytes((paymentIntentId + "_" + stripeStatus).getBytes(StandardCharsets.UTF_8));

        MockStripeWebhookPayload payload = new MockStripeWebhookPayload(
              eventId,
              eventType,
              new Data(objectData)
        );

        log.info(
              "MockStripe: triggering async HTTP webhook [{}] to [{}] for intent [{}]",
              eventType,
              mockPspProperties.getWebhookUrl(),
              paymentIntentId
        );

        CompletableFuture.runAsync(() -> postWebhookWithRetry(payload, paymentIntentId, 1), webhookDeliveryExecutor);

        return new ConfirmPaymentIntentResponse(paymentIntentId, stripeStatus, intent.clientSecret());
    }
}