package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.PaymentError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Component
@RequiredArgsConstructor
public class MockStripeWebhookDispatcher {

    private final RestClient restClient;
    private final Executor webhookDeliveryExecutor;
    private final MockPspProperties mockPspProperties;
    private final ObjectMapper objectMapper;

    private static final int MAX_WEBHOOK_ATTEMPTS = 3;

    /**
     * Computes a hex-formatted HMAC-SHA256 signature over one or more byte arrays.
     */
    static String hmacHex(@lombok.NonNull String secret, byte[]... byteArrays) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            for (byte[] bytes : byteArrays)
                mac.update(bytes);

            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void postWebhookWithRetry(byte[] body,
                                      String paymentIntentId,
                                      int attempt) {
        long t = Instant.now().getEpochSecond();

        // Stripe webhook signature requires HMAC over "timestamp.payload"
        byte[] prefixBytes = (t + ".").getBytes(UTF_8);
        String signature = hmacHex(mockPspProperties.getWebhookSecret(), prefixBytes, body);
        String sigHeader = "t=" + t + ",v1=" + signature;

        try {
            restClient.post()
                  .uri(mockPspProperties.getWebhookUrl())
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(body)
                  .header("Stripe-Signature", sigHeader)
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

            postWebhookWithRetry(body, paymentIntentId, attempt + 1);
        }
    }

    public void dispatchWebhookAsync(@lombok.NonNull String paymentIntentId,
                                     @lombok.NonNull Long amountCents,
                                     @lombok.NonNull MockPaymentIntentStatus intentStatus,
                                     PaymentError error,
                                     int attemptCount) {

        String stripeStatus = intentStatus.getValue();
        String eventType = intentStatus.getWebhookEventType();

        ObjectData objectData = new ObjectData(
              paymentIntentId,
              amountCents,
              "mad",
              stripeStatus,
              error
        );

        // Deterministic eventId ensuring idempotency across distinct attempts
        String eventId = "evt_mock_" + UUID.nameUUIDFromBytes(
              (paymentIntentId + "_" + stripeStatus + "_" + attemptCount).getBytes(UTF_8)
        );

        MockStripeWebhookPayload payload = new MockStripeWebhookPayload(eventId, eventType, new Data(objectData));

        log.info(
              "MockStripe: triggering async HTTP webhook [{}] to [{}] for intent [{}]",
              eventType,
              mockPspProperties.getWebhookUrl(),
              paymentIntentId
        );

        byte[] body;

        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            log.error("Cannot serialize webhook for intent [{}]", paymentIntentId, e);
            return;
        }

        try {
            CompletableFuture.runAsync(() -> postWebhookWithRetry(body, paymentIntentId, 1), webhookDeliveryExecutor)
                  .exceptionally(ex -> {
                      log.error("Webhook task failed for intent [{}]", paymentIntentId, ex);
                      return null;
                  });
        } catch (RejectedExecutionException e) {
            log.error("Webhook dropped, executor saturated for intent [{}]", paymentIntentId, e);
        }
    }
}