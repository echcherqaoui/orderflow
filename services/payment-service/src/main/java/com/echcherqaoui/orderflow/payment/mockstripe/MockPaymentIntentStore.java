package com.echcherqaoui.orderflow.payment.mockstripe;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MockPaymentIntentStore {

    private final Map<String, MockPaymentIntent> intentsByPaymentIntentId = new ConcurrentHashMap<>();
    private final Map<String, MockPaymentIntent> intentsByIdempotencyKey = new ConcurrentHashMap<>();

    public void save(@NonNull String idempotencyKey, @NonNull MockPaymentIntent intent) {
        intentsByPaymentIntentId.put(intent.paymentIntentId(), intent);

        if (!idempotencyKey.isBlank())
            intentsByIdempotencyKey.put(idempotencyKey, intent);
    }

    public Optional<MockPaymentIntent> findByIdempotencyKey(@NonNull String idempotencyKey) {
        return Optional.ofNullable(intentsByIdempotencyKey.get(idempotencyKey));
    }

    public Optional<MockPaymentIntent> findByPaymentIntentId(@NonNull String paymentIntentId) {
        return Optional.ofNullable(intentsByPaymentIntentId.get(paymentIntentId));
    }

    public void remove(@NonNull String paymentIntentId) {
        MockPaymentIntent removed = intentsByPaymentIntentId.remove(paymentIntentId);

        if (removed != null)
            intentsByIdempotencyKey.values()
                  .removeIf(intent -> intent.paymentIntentId().equals(paymentIntentId));
    }
}