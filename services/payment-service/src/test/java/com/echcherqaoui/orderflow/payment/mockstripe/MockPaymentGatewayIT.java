package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MockPaymentGatewayIT extends AbstractIntegrationTest {

    @Autowired
    private MockPaymentGateway mockPaymentGateway;

    @Autowired
    private MockPaymentIntentStore intentStore;

    private final long totalAmountCents = 15000L;

    @Nested
    @DisplayName("createIntent()")
    class CreateIntent {

        @Test
        @DisplayName("creates and persists new payment intent when key is not present in store")
        void createIntent_newKey_createsAndStoresIntent() {
            String idempotencyKey = UUID.randomUUID().toString();

            CreatePaymentIntentResponse response = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);

            assertThat(response).isNotNull();
            assertThat(response.paymentIntentId()).isNotBlank();
            assertThat(response.clientSecret()).isNotBlank();

            Optional<MockPaymentIntent> storedIntent = intentStore.findByIdempotencyKey(idempotencyKey);
            assertThat(storedIntent).isPresent();
            assertThat(storedIntent.get().paymentIntentId()).isEqualTo(response.paymentIntentId());
            assertThat(storedIntent.get().clientSecret()).isEqualTo(response.clientSecret());
        }

        @Test
        @DisplayName("returns existing payment intent without creating new one when idempotency key recurs")
        void createIntent_duplicateKey_returnsExistingIntent() {
            String idempotencyKey = UUID.randomUUID().toString();

            CreatePaymentIntentResponse firstCallResponse = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);
            CreatePaymentIntentResponse secondCallResponse = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);

            assertThat(secondCallResponse.paymentIntentId()).isEqualTo(firstCallResponse.paymentIntentId());
            assertThat(secondCallResponse.clientSecret()).isEqualTo(firstCallResponse.clientSecret());

            Optional<MockPaymentIntent> storedIntent = intentStore.findByIdempotencyKey(idempotencyKey);
            assertThat(storedIntent).isPresent();
            assertThat(storedIntent.get().paymentIntentId()).isEqualTo(firstCallResponse.paymentIntentId());
        }
    }

    @Nested
    @DisplayName("cancelIntent()")
    class CancelIntent {

        @Test
        @DisplayName("removes intent from store by paymentIntentId and clears idempotency mapping")
        void cancelIntent_existingIntent_removesFromStore() {
            String idempotencyKey = UUID.randomUUID().toString();
            CreatePaymentIntentResponse created = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);

            mockPaymentGateway.cancelIntent(created.paymentIntentId());

            assertThat(intentStore.findByPaymentIntentId(created.paymentIntentId())).isEmpty();
            assertThat(intentStore.findByIdempotencyKey(idempotencyKey)).isEmpty();
        }

        @Test
        @DisplayName("executes without exception when paymentIntentId does not exist")
        void cancelIntent_nonExistingIntent_handlesGracefully() {
            String nonExistentIntentId = "pi_non_existent_" + UUID.randomUUID();

            mockPaymentGateway.cancelIntent(nonExistentIntentId);

            assertThat(intentStore.findByPaymentIntentId(nonExistentIntentId)).isEmpty();
        }
    }
}