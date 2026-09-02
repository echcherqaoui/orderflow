package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MockPaymentGatewayTest {

    @Mock
    private MockPaymentIntentStore intentStore;

    @InjectMocks
    private MockPaymentGateway mockPaymentGateway;

    @Captor
    private ArgumentCaptor<MockPaymentIntent> intentCaptor;

    private final String idempotencyKey = UUID.randomUUID().toString();
    private final long totalAmountCents = 5000L;
    private final String existingPaymentIntentId = "pi_mock_123";

    @Nested
    @DisplayName("createIntent()")
    class CreateIntent {

        @Test
        @DisplayName("existing idempotency key returns cached intent details without saving new intent")
        void createIntent_existingIdempotencyKey_returnsCachedResponse() {
            String existingClientSecret = "secret_mock_123";
            MockPaymentIntent existingIntent = new MockPaymentIntent(
                  existingPaymentIntentId,
                  existingClientSecret,
                  totalAmountCents,
                  MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD
            );
            given(intentStore.findByIdempotencyKey(idempotencyKey)).willReturn(Optional.of(existingIntent));

            CreatePaymentIntentResponse response = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);

            assertThat(response).isNotNull();
            assertThat(response.paymentIntentId()).isEqualTo(existingPaymentIntentId);
            assertThat(response.clientSecret()).isEqualTo(existingClientSecret);

            then(intentStore).should().findByIdempotencyKey(idempotencyKey);
            then(intentStore).should(never()).save(any(), any());
        }

        @Test
        @DisplayName("new idempotency key creates, persists, and returns new payment intent response")
        void createIntent_newIdempotencyKey_createsAndPersistsNewIntent() {
            given(intentStore.findByIdempotencyKey(idempotencyKey)).willReturn(Optional.empty());

            CreatePaymentIntentResponse response = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);

            assertThat(response).isNotNull();
            assertThat(response.paymentIntentId()).isNotBlank();
            assertThat(response.clientSecret()).isNotBlank();

            then(intentStore).should().findByIdempotencyKey(idempotencyKey);
            then(intentStore).should().save(eq(idempotencyKey), intentCaptor.capture());

            MockPaymentIntent savedIntent = intentCaptor.getValue();
            assertThat(savedIntent.paymentIntentId()).isEqualTo(response.paymentIntentId());
            assertThat(savedIntent.clientSecret()).isEqualTo(response.clientSecret());
        }
    }

    @Nested
    @DisplayName("cancelIntent()")
    class CancelIntent {

        @Test
        @DisplayName("delegates cancellation to intent store remove")
        void cancelIntent_delegatesToStoreRemove() {
            mockPaymentGateway.cancelIntent(existingPaymentIntentId);

            then(intentStore).should().remove(existingPaymentIntentId);
        }
    }
}