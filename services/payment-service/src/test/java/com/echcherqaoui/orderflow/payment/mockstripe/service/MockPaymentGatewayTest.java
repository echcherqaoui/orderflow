package com.echcherqaoui.orderflow.payment.mockstripe.service;

import com.echcherqaoui.orderflow.payment.dto.RefundCreateParams;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayPermanentException;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntent;
import com.echcherqaoui.orderflow.payment.mockstripe.store.MockPaymentIntentStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntentStatus.REFUNDED;
import static com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        @DisplayName("delegates atomic creation/retrieval to getOrCreate when outage is false")
        void createIntent_successfulExecution_returnsResponseFromStore() {
            given(mockPspProperties.isSimulateOutage()).willReturn(false);

            MockPaymentIntent mockIntent = new MockPaymentIntent(
                  paymentIntentId,
                  idempotencyKey,
                  clientSecret,
                  totalAmountCents,
                  REQUIRES_PAYMENT_METHOD,
                  0,
                  null
            );
            given(intentStore.getOrCreate(idempotencyKey, totalAmountCents)).willReturn(mockIntent);

            CreatePaymentIntentResponse response = mockPaymentGateway.createIntent(idempotencyKey, totalAmountCents);

            assertThat(response).isNotNull();
            assertThat(response.paymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(response.clientSecret()).isEqualTo(clientSecret);

            then(intentStore).should().getOrCreate(idempotencyKey, totalAmountCents);
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

    @Nested
    @DisplayName("refundPayment()")
    class RefundPayment {

        private final String triggerEventId = UUID.randomUUID().toString();
        private final RefundCreateParams params = new RefundCreateParams(
              paymentIntentId,
              "Order canceled by customer",
              triggerEventId
        );

        @Test
        @DisplayName("throws PaymentGatewayTransientException when simulateOutage is true")
        void refundPayment_simulateOutageTrue_throwsTransientException() {
            given(mockPspProperties.isSimulateOutage()).willReturn(true);

            assertThatThrownBy(() -> mockPaymentGateway.refundPayment(params))
                  .isInstanceOf(PaymentGatewayTransientException.class)
                  .hasMessage("Simulated PSP outage");

            then(intentStore).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("successfully processes refund when intent is valid")
        void refundPayment_validIntent_executesRefundSuccessfully() {
            given(mockPspProperties.isSimulateOutage()).willReturn(false);

            MockPaymentIntent refundedIntent = new MockPaymentIntent(
                  paymentIntentId,
                  idempotencyKey,
                  clientSecret,
                  totalAmountCents,
                  REFUNDED,
                  0,
                  null
            );
            given(intentStore.refund(paymentIntentId)).willReturn(refundedIntent);

            assertThatCode(() -> mockPaymentGateway.refundPayment(params))
                  .doesNotThrowAnyException();

            then(intentStore).should().refund(paymentIntentId);
        }

        @Test
        @DisplayName("throws PaymentGatewayPermanentException when intent is not found")
        void refundPayment_intentNotFound_throwsPermanentException() {
            given(mockPspProperties.isSimulateOutage()).willReturn(false);
            given(intentStore.refund(paymentIntentId)).willReturn(null);

            assertThatThrownBy(() -> mockPaymentGateway.refundPayment(params))
                  .isInstanceOf(PaymentGatewayPermanentException.class)
                  .hasMessage("No such payment_intent: " + paymentIntentId);

            then(intentStore).should().refund(paymentIntentId);
        }

        @Test
        @DisplayName("wraps IllegalStateException from store into PaymentGatewayPermanentException")
        void refundPayment_storeThrowsIllegalState_throwsPermanentException() {
            given(mockPspProperties.isSimulateOutage()).willReturn(false);
            given(intentStore.refund(paymentIntentId))
                  .willThrow(new IllegalStateException("Charge already refunded"));

            assertThatThrownBy(() -> mockPaymentGateway.refundPayment(params))
                  .isInstanceOf(PaymentGatewayPermanentException.class)
                  .hasMessage("Charge already refunded")
                  .hasCauseInstanceOf(IllegalStateException.class);

            then(intentStore).should().refund(paymentIntentId);
        }
    }
}