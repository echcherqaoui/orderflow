package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;

@Component
public class MockPaymentIntentStore {

    private final Map<String, MockPaymentIntent> intentsByPaymentIntentId = new ConcurrentHashMap<>();
    private final Map<String, MockPaymentIntent> intentsByIdempotencyKey = new ConcurrentHashMap<>();

    public MockPaymentIntent getOrCreate(@lombok.NonNull String idempotencyKey, long totalAmountCents) {
        Function<String, MockPaymentIntent> createAndStoreIntent = key -> {
            MockPaymentIntent newIntent = MockPaymentIntent.create(idempotencyKey, totalAmountCents);
            intentsByPaymentIntentId.put(newIntent.paymentIntentId(), newIntent);

            return newIntent;
        };

        return intentsByIdempotencyKey.computeIfAbsent(idempotencyKey, createAndStoreIntent);
    }

    public TransitionResult recordAttempt(String paymentIntentId,
                                          String clientSecret,
                                          boolean isSuccess,
                                          String errorCode,
                                          int maxAllowedAttempts) {
        // Idempotency flag: true only if state transitioned, signaling MockStripeService to fire a webhook.
        AtomicBoolean applied = new AtomicBoolean(false);

        BiFunction<String, MockPaymentIntent, MockPaymentIntent> applyAttempt = (id, current) -> {
            if (!current.clientSecret().equals(clientSecret))
                throw new IllegalArgumentException("Invalid client_secret provided for payment_intent: " + paymentIntentId);

            // Only attempt transitions when intent is active (REQUIRES_PAYMENT_METHOD)
            if (current.status() != REQUIRES_PAYMENT_METHOD)
                return current;

            applied.set(true);

            if (isSuccess)
                return current.withSuccess();

            int nextAttempt = current.attemptCount() + 1;

            // Terminal decision: Fraud or max retry limit reached
            if (nextAttempt >= maxAllowedAttempts || "fraudulent".equalsIgnoreCase(errorCode)) {
                String terminalCode = errorCode != null ? errorCode : "max_attempts_exceeded";

                return current.withTerminalFailure(terminalCode);
            }

            // Transient retryable failure
            String transientCode = errorCode != null ? errorCode : "card_declined";

            return current.withTransientFailure(transientCode);
        };

        MockPaymentIntent result = intentsByPaymentIntentId.computeIfPresent(paymentIntentId, applyAttempt);

        return new TransitionResult(result, applied.get());
    }

    public void remove(@lombok.NonNull String paymentIntentId) {
        MockPaymentIntent removed = intentsByPaymentIntentId.remove(paymentIntentId);

        if (removed != null && removed.idempotencyKey() != null)
            intentsByIdempotencyKey.remove(removed.idempotencyKey());
    }
}