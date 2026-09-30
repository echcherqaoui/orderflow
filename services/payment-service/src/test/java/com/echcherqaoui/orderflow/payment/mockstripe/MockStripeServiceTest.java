package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentRequest;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.PaymentError;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.CANCELED;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MockStripeServiceTest {

    @Mock
    private MockPaymentIntentStore intentStore;

    @Mock
    private MockStripePaymentProcessor paymentProcessor;

    @Mock
    private MockStripeWebhookDispatcher webhookDispatcher;

    @Mock
    private MockPspProperties mockPspProperties;

    private MockStripeService mockStripeService;

    private static final String PAYMENT_INTENT_ID = "pi_mock_123456";
    private static final String IDEMPOTENCY_KEY = "idem_key_789";
    private static final String CLIENT_SECRET = "secret_mock_654321";
    private static final long AMOUNT_CENTS = 5000L;
    private static final int MAX_CONFIRM_ATTEMPTS = 3;

    @BeforeEach
    void setUp() {
        mockStripeService = new MockStripeService(intentStore, paymentProcessor, webhookDispatcher, mockPspProperties);
    }

    @Nested
    @DisplayName("Validation and Lookup Failures")
    class ValidationAndLookupFailures {

        @Test
        @DisplayName("throws IllegalArgumentException when payment intent is not found")
        void confirm_notFound_throwsException() {
            given(mockPspProperties.getMaxConfirmAttempts()).willReturn(MAX_CONFIRM_ATTEMPTS);
            given(intentStore.recordAttempt(eq(PAYMENT_INTENT_ID), eq(CLIENT_SECRET), any(Boolean.class), any(), eq(MAX_CONFIRM_ATTEMPTS)))
                  .willReturn(new TransitionResult(null, false));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_visa");

            assertThatThrownBy(() -> mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("No such payment_intent: " + PAYMENT_INTENT_ID);

            verifyNoInteractions(webhookDispatcher);
        }

        @Test
        @DisplayName("re-throws IllegalArgumentException when recordAttempt fails due to client secret mismatch")
        void confirm_invalidClientSecret_throwsException() {
            given(paymentProcessor.decideOutcome("pm_card_visa")).willReturn(true);
            given(mockPspProperties.getMaxConfirmAttempts()).willReturn(MAX_CONFIRM_ATTEMPTS);

            given(intentStore.recordAttempt(PAYMENT_INTENT_ID, "invalid_secret", true, null, MAX_CONFIRM_ATTEMPTS))
                  .willThrow(new IllegalArgumentException("Invalid client_secret provided for payment_intent: " + PAYMENT_INTENT_ID));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest("invalid_secret", "pm_card_visa");

            assertThatThrownBy(() -> mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Invalid client_secret provided for payment_intent: " + PAYMENT_INTENT_ID);

            verifyNoInteractions(webhookDispatcher);
        }
    }

    @Nested
    @DisplayName("Successful Confirmation & Webhook Execution")
    class SuccessfulConfirmation {

        @Test
        @DisplayName("confirms intent successfully and dispatches webhook via dispatcher")
        void confirm_success_updatesStatusAndDispatchesWebhook() {
            given(paymentProcessor.decideOutcome("pm_card_visa")).willReturn(true);
            given(mockPspProperties.getMaxConfirmAttempts()).willReturn(MAX_CONFIRM_ATTEMPTS);

            MockPaymentIntent updatedIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  IDEMPOTENCY_KEY,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  SUCCEEDED,
                  1,
                  null
            );

            given(intentStore.recordAttempt(PAYMENT_INTENT_ID, CLIENT_SECRET, true, null, MAX_CONFIRM_ATTEMPTS))
                  .willReturn(new TransitionResult(updatedIntent, true));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_visa");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo("SUCCEEDED");
            assertThat(response.clientSecret()).isEqualTo(CLIENT_SECRET);

            then(webhookDispatcher).should().dispatchWebhookAsync(
                  eq(PAYMENT_INTENT_ID),
                  eq(AMOUNT_CENTS),
                  eq(SUCCEEDED),
                  eq(null),
                  eq(1)
            );
        }
    }

    @Nested
    @DisplayName("Declined Payment Outcomes & Webhook Execution")
    class DeclinedOutcomes {

        @Test
        @DisplayName("handles declined card, maps error, and dispatches webhook with error")
        void confirm_chargeDeclined_mapsErrorAndDispatchesWebhook() {
            PaymentError cardDeclinedError = new PaymentError("card_declined", "Your card was declined.");
            given(paymentProcessor.decideOutcome("pm_card_chargeDeclined")).willReturn(false);
            given(paymentProcessor.resolvePaymentError("pm_card_chargeDeclined")).willReturn(cardDeclinedError);
            given(mockPspProperties.getMaxConfirmAttempts()).willReturn(MAX_CONFIRM_ATTEMPTS);

            MockPaymentIntent updatedIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  IDEMPOTENCY_KEY,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  REQUIRES_PAYMENT_METHOD,
                  1,
                  "card_declined"
            );

            given(intentStore.recordAttempt(PAYMENT_INTENT_ID, CLIENT_SECRET, false, "card_declined", MAX_CONFIRM_ATTEMPTS))
                  .willReturn(new TransitionResult(updatedIntent, true));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_chargeDeclined");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo("REQUIRES_PAYMENT_METHOD");
            assertThat(response.clientSecret()).isEqualTo(CLIENT_SECRET);

            then(webhookDispatcher).should().dispatchWebhookAsync(
                  eq(PAYMENT_INTENT_ID),
                  eq(AMOUNT_CENTS),
                  eq(REQUIRES_PAYMENT_METHOD),
                  eq(cardDeclinedError),
                  eq(1)
            );
        }

        @Test
        @DisplayName("dispatches CANCELED status webhook when max confirm attempts are reached")
        void confirm_maxAttemptsReached_transitionsToCanceled() {
            PaymentError maxAttemptsError = new PaymentError("card_declined", "Your card was declined.");
            given(paymentProcessor.decideOutcome("pm_card_chargeDeclined")).willReturn(false);
            given(paymentProcessor.resolvePaymentError("pm_card_chargeDeclined")).willReturn(maxAttemptsError);
            given(mockPspProperties.getMaxConfirmAttempts()).willReturn(MAX_CONFIRM_ATTEMPTS);

            MockPaymentIntent canceledIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  IDEMPOTENCY_KEY,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  CANCELED,
                  MAX_CONFIRM_ATTEMPTS,
                  "card_declined"
            );

            given(intentStore.recordAttempt(PAYMENT_INTENT_ID, CLIENT_SECRET, false, "card_declined", MAX_CONFIRM_ATTEMPTS))
                  .willReturn(new TransitionResult(canceledIntent, true));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_chargeDeclined");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo("CANCELED");

            then(webhookDispatcher).should().dispatchWebhookAsync(
                  eq(PAYMENT_INTENT_ID),
                  eq(AMOUNT_CENTS),
                  eq(CANCELED),
                  eq(maxAttemptsError),
                  eq(MAX_CONFIRM_ATTEMPTS)
            );
        }
    }

    @Nested
    @DisplayName("Idempotency and Non-Applied Transitions")
    class IdempotencyHandling {

        @Test
        @DisplayName("returns current intent state without triggering webhooks when transition was not applied")
        void confirm_transitionNotApplied_returnsCurrentStateWithoutWebhook() {
            given(paymentProcessor.decideOutcome("pm_card_visa")).willReturn(true);
            given(mockPspProperties.getMaxConfirmAttempts()).willReturn(MAX_CONFIRM_ATTEMPTS);

            MockPaymentIntent existingSucceededIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  IDEMPOTENCY_KEY,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  SUCCEEDED,
                  1,
                  null
            );

            given(intentStore.recordAttempt(PAYMENT_INTENT_ID, CLIENT_SECRET, true, null, MAX_CONFIRM_ATTEMPTS))
                  .willReturn(new TransitionResult(existingSucceededIntent, false));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_visa");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo(SUCCEEDED.getValue());
            assertThat(response.clientSecret()).isEqualTo(CLIENT_SECRET);

            verifyNoInteractions(webhookDispatcher);
        }
    }
}