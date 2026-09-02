package com.echcherqaoui.orderflow.payment.mockstripe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentIntentStoreTest {

    private MockPaymentIntentStore store;

    @BeforeEach
    void setUp() {
        store = new MockPaymentIntentStore();
    }

    @Test
    @DisplayName("Should store payment intent in both primary and idempotency maps when key is valid")
    void saveAndFind_withValidIdempotencyKey_storesInBothMaps() {
        MockPaymentIntent intent = new MockPaymentIntent("pi_123", "secret_123", 1000L, REQUIRES_PAYMENT_METHOD);

        store.save("idempotency_123", intent);

        assertThat(store.findByPaymentIntentId("pi_123")).contains(intent);
        assertThat(store.findByIdempotencyKey("idempotency_123")).contains(intent);
    }

    @Test
    @DisplayName("Should store payment intent only in primary map when idempotency key is blank")
    void save_withBlankIdempotencyKey_doesNotStoreInIdempotencyMap() {
        MockPaymentIntent intent = new MockPaymentIntent("pi_123", "secret_123", 1000L, REQUIRES_PAYMENT_METHOD);

        store.save("   ", intent);

        assertThat(store.findByPaymentIntentId("pi_123")).contains(intent);
        assertThat(store.findByIdempotencyKey("   ")).isEmpty();
    }

    @Test
    @DisplayName("Should remove payment intent from both maps when removed by payment intent ID")
    void remove_cleansUpBothMaps() {
        MockPaymentIntent intent = new MockPaymentIntent("pi_123", "secret_123", 1000L, REQUIRES_PAYMENT_METHOD);
        store.save("idempotency_123", intent);

        store.remove("pi_123");

        assertThat(store.findByPaymentIntentId("pi_123")).isEmpty();
        assertThat(store.findByIdempotencyKey("idempotency_123")).isEmpty();
    }
}