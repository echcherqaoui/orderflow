package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MockPaymentGatewayTest {

    @Mock
    private MockPaymentIntentStore intentStore;

    @Mock
    private MockPspProperties mockPspProperties;

    @InjectMocks
    private MockPaymentGateway mockPaymentGateway;

    private final String idempotencyKey = UUID.randomUUID().toString();
    private final long totalAmountCents = 5000L;
    private final String paymentIntentId = "pi_mock_123";
    private final String clientSecret = "secret_mock_123";

    @Nested
    @DisplayName("createIntent()")
    class CreateIntent {

        @Test
        @DisplayName("delegates atomic creation/retrieval to computeIfAbsent when outage is false")
        void createIntent_successfulExecution_returnsResponseFromStore() {
            given(mockPspProperties.isSimulateOutage()).willReturn(false);

            MockPaymentIntent mockIntent = new MockPaymentIntent(
                  paymentIntentId,
                  clientSecret,
                  totalAmountCents,
                  MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD
            );
            given(intentStore.computeIfAbsent(idempotencyKey, totalAmountCents)).willReturn(mockIntent);

            CreatePaymentIntentResponse response = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);

            assertThat(response).isNotNull();
            assertThat(response.paymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(response.clientSecret()).isEqualTo(clientSecret);

            then(intentStore).should().computeIfAbsent(idempotencyKey, totalAmountCents);
        }

        @Test
        @DisplayName("throws PaymentGatewayTransientException when simulateOutage is true")
        void createIntent_simulateOutageTrue_throwsTransientException() {
            given(mockPspProperties.isSimulateOutage()).willReturn(true);

            assertThatThrownBy(() -> mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents))
                  .isInstanceOf(PaymentGatewayTransientException.class);

            then(intentStore).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("cancelIntent()")
    class CancelIntent {

        @Test
        @DisplayName("delegates cancellation to intent store remove when ID is valid")
        void cancelIntent_validId_delegatesToStoreRemove() {
            mockPaymentGateway.cancelIntent(paymentIntentId);

            then(intentStore).should().remove(paymentIntentId);
        }

        @Test
        @DisplayName("bypasses store removal when paymentIntentId is null or blank")
        void cancelIntent_nullOrBlankId_skipsStoreRemove() {
            mockPaymentGateway.cancelIntent(null);
            mockPaymentGateway.cancelIntent("   ");

            then(intentStore).should(never()).remove(anyString());
        }
    }
}