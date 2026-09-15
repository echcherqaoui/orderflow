package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.exception.domain.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class MockPaymentGatewayIT  implements WithPostgres {

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

        @Test
        @DisplayName("throws PaymentGatewayTransientException when PSP outage is simulated")
        void createIntent_simulateOutageTrue_throwsException() {
            String idempotencyKey = UUID.randomUUID().toString();

            try {
                ReflectionTestUtils.setField(mockPaymentGateway, "simulatePspOutage", true);

                assertThatThrownBy(() -> mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents))
                      .isInstanceOf(PaymentGatewayTransientException.class);
            } finally {
                ReflectionTestUtils.setField(mockPaymentGateway, "simulatePspOutage", false);
            }
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

            assertThatCode(() -> mockPaymentGateway.cancelIntent(nonExistentIntentId))
                  .doesNotThrowAnyException();

            assertThat(intentStore.findByPaymentIntentId(nonExistentIntentId)).isEmpty();
        }
    }
}