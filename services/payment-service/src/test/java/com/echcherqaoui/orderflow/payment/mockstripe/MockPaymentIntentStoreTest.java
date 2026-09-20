package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentIntentStoreTest {

    private MockPaymentIntentStore store;

    @BeforeEach
    void setUp() {
        store = new MockPaymentIntentStore();
    }

    @Test
    @DisplayName("Should create and retrieve payment intent atomically given a new idempotency key")
    void computeIfAbsent_withNewKey_createsAndStoresIntent() {
        String idempotencyKey = "idempotency_123";
        long amount = 1000L;

        MockPaymentIntent created = store.computeIfAbsent(idempotencyKey, amount);

        assertThat(created).isNotNull();
        assertThat(created.totalAmountCents()).isEqualTo(amount);
        assertThat(created.status()).isEqualTo(REQUIRES_PAYMENT_METHOD);
        assertThat(created.paymentIntentId()).isNotBlank();
        assertThat(created.clientSecret()).isNotBlank();
    }

    @Test
    @DisplayName("Should return existing payment intent without creating a new one when idempotency key recurs")
    void computeIfAbsent_withExistingKey_returnsSameIntent() {
        String idempotencyKey = "idempotency_123";

        MockPaymentIntent firstCall = store.computeIfAbsent(idempotencyKey, 1000L);
        MockPaymentIntent secondCall = store.computeIfAbsent(idempotencyKey, 1000L);

        assertThat(secondCall).isSameAs(firstCall);
    }

    @Test
    @DisplayName("Should apply state transition when status is REQUIRES_PAYMENT_METHOD")
    void transitionIfPending_whenPending_appliesTransition() {
        MockPaymentIntent initial = store.computeIfAbsent("idempotency_123", 1000L);

        TransitionResult result = store.transitionIfPending(
              initial.paymentIntentId(),
              current -> new MockPaymentIntent(
                    current.paymentIntentId(),
                    current.clientSecret(),
                    current.totalAmountCents(),
                    SUCCEEDED
              )
        );

        assertThat(result.applied()).isTrue();
        assertThat(result.intent().status()).isEqualTo(SUCCEEDED);
    }

    @Test
    @DisplayName("Should skip state transition when intent is already in a terminal state")
    void transitionIfPending_whenAlreadyTerminal_skipsTransition() {
        MockPaymentIntent initial = store.computeIfAbsent("idempotency_123", 1000L);

        // First transition to terminal state
        store.transitionIfPending(
              initial.paymentIntentId(),
              current -> new MockPaymentIntent(
                    current.paymentIntentId(),
                    current.clientSecret(),
                    current.totalAmountCents(),
                    SUCCEEDED
              )
        );

        // Second transition attempt
        TransitionResult secondAttempt = store.transitionIfPending(
              initial.paymentIntentId(),
              current -> new MockPaymentIntent(
                    current.paymentIntentId(),
                    current.clientSecret(),
                    current.totalAmountCents(),
                    REQUIRES_PAYMENT_METHOD
              )
        );

        assertThat(secondAttempt.applied()).isFalse();
        assertThat(secondAttempt.intent().status()).isEqualTo(SUCCEEDED);
    }

    @Test
    @DisplayName("Should remove intent from both primary and idempotency mappings")
    void remove_cleansUpBothMappings() {
        String idempotencyKey = "idempotency_123";
        MockPaymentIntent intent = store.computeIfAbsent(idempotencyKey, 1000L);

        store.remove(intent.paymentIntentId());

        // Verify key cleanup: computeIfAbsent creates a brand-new instance for the same idempotency key
        MockPaymentIntent brandNewIntent = store.computeIfAbsent(idempotencyKey, 1000L);
        assertThat(brandNewIntent.paymentIntentId()).isNotEqualTo(intent.paymentIntentId());
    }
}