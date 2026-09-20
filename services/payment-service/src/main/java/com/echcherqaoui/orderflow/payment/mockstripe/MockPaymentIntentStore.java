package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

@Component
public class MockPaymentIntentStore {

    private final Map<String, MockPaymentIntent> intentsByPaymentIntentId = new ConcurrentHashMap<>();
    private final Map<String, MockPaymentIntent> intentsByIdempotencyKey = new ConcurrentHashMap<>();

    public MockPaymentIntent computeIfAbsent(@lombok.NonNull String idempotencyKey, long totalAmountCents) {
        return intentsByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            MockPaymentIntent newIntent = MockPaymentIntent.create(totalAmountCents);
            intentsByPaymentIntentId.put(newIntent.paymentIntentId(), newIntent);
            return newIntent;
        });
    }

    public TransitionResult transitionIfPending(String paymentIntentId,
                                                UnaryOperator<MockPaymentIntent> transition) {
        AtomicBoolean applied = new AtomicBoolean(false);

        BiFunction<String, MockPaymentIntent, MockPaymentIntent> applyTransitionIfPending = (id, current) -> {
            if (current.status() != MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD) return current;

            applied.set(true);
            return transition.apply(current);
        };

        MockPaymentIntent result = intentsByPaymentIntentId.computeIfPresent(paymentIntentId, applyTransitionIfPending);

        return new TransitionResult(result, applied.get());
    }

    public void remove(@lombok.NonNull String paymentIntentId) {
        BiFunction<String, MockPaymentIntent, MockPaymentIntent> removeFromBothMaps = (id, intent) -> {
            intentsByIdempotencyKey.values().removeIf(i -> i.paymentIntentId().equals(paymentIntentId));
            return null;
        };

        intentsByPaymentIntentId.computeIfPresent(paymentIntentId, removeFromBothMaps);
    }
}