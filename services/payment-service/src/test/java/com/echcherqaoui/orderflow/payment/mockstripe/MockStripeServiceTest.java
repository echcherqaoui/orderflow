package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentRequest;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MockStripeServiceTest {

    @Mock
    private MockPaymentIntentStore intentStore;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private MockPspProperties mockPspProperties;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Captor
    private ArgumentCaptor<UnaryOperator<MockPaymentIntent>> transitionCaptor;

    @Captor
    private ArgumentCaptor<MockStripeWebhookPayload> payloadCaptor;

    private MockStripeService mockStripeService;

    // Synchronous executor for deterministic async testing
    private final Executor sameThreadExecutor = Runnable::run;

    private static final String PAYMENT_INTENT_ID = "pi_mock_123456";
    private static final String CLIENT_SECRET = "secret_mock_654321";
    private static final String TEST_WEBHOOK_URL = "http://example.com/webhooks/stripe";
    private static final long AMOUNT_CENTS = 5000L;

    @BeforeEach
    void setUp() {
        mockStripeService = new MockStripeService(intentStore, restClient, sameThreadExecutor, mockPspProperties);
    }

    private void mockRestClientPostCall() {
        given(mockPspProperties.getWebhookUrl()).willReturn(TEST_WEBHOOK_URL);

        given(restClient.post()).willReturn(requestBodyUriSpec);
        given(requestBodyUriSpec.uri(TEST_WEBHOOK_URL)).willReturn(requestBodySpec);
        given(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).willReturn(requestBodySpec);
        given(requestBodySpec.body(any(MockStripeWebhookPayload.class))).willReturn(requestBodySpec);
        given(requestBodySpec.retrieve()).willReturn(responseSpec);
    }

    @Nested
    @DisplayName("Validation and Lookup Failures")
    class ValidationAndLookupFailures {

        @Test
        @DisplayName("throws IllegalArgumentException when payment intent is not found")
        void confirm_notFound_throwsException() {
            given(intentStore.transitionIfPending(eq(PAYMENT_INTENT_ID), any()))
                  .willReturn(new TransitionResult(null, false));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_visa");

            assertThatThrownBy(() -> mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("No such payment_intent: " + PAYMENT_INTENT_ID);

            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("throws IllegalArgumentException when client_secret does not match inside state transition")
        void confirm_invalidClientSecret_throwsException() {
            MockPaymentIntent existingIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  REQUIRES_PAYMENT_METHOD
            );

            given(intentStore.transitionIfPending(eq(PAYMENT_INTENT_ID), transitionCaptor.capture()))
                  .willAnswer(invocation -> {
                      UnaryOperator<MockPaymentIntent> operator = invocation.getArgument(1);
                      operator.apply(existingIntent);
                      return null;
                  });

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest("invalid_secret", "pm_card_visa");

            assertThatThrownBy(() -> mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Invalid client_secret provided for payment_intent: " + PAYMENT_INTENT_ID);

            verifyNoInteractions(restClient);
        }
    }

    @Nested
    @DisplayName("Successful Confirmation & Webhook Execution")
    class SuccessfulConfirmation {

        @Test
        @DisplayName("confirms intent successfully and dispatches payment_intent.succeeded webhook")
        void confirm_success_updatesStatusAndDispatchesWebhook() {
            MockPaymentIntent initialIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  REQUIRES_PAYMENT_METHOD
            );

            given(intentStore.transitionIfPending(eq(PAYMENT_INTENT_ID), transitionCaptor.capture()))
                  .willAnswer(invocation -> {
                      UnaryOperator<MockPaymentIntent> operator = invocation.getArgument(1);
                      MockPaymentIntent updated = operator.apply(initialIntent);
                      return new TransitionResult(updated, true);
                  });

            mockRestClientPostCall();

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_visa");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo("succeeded");
            assertThat(response.clientSecret()).isEqualTo(CLIENT_SECRET);

            then(restClient).should().post();
            then(requestBodyUriSpec).should().uri(TEST_WEBHOOK_URL);
            then(requestBodySpec).should().contentType(MediaType.APPLICATION_JSON);
            then(requestBodySpec).should().body(payloadCaptor.capture());

            MockStripeWebhookPayload payload = payloadCaptor.getValue();
            assertThat(payload.type()).isEqualTo("payment_intent.succeeded");
            assertThat(payload.data().object().id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(payload.data().object().status()).isEqualTo("succeeded");
            assertThat(payload.data().object().lastPaymentError()).isNull();
        }
    }

    @Nested
    @DisplayName("Declined Payment Outcomes & Webhook Execution")
    class DeclinedOutcomes {

        @Test
        @DisplayName("handles declined card, maps card_declined error, and sends payment_failed webhook")
        void confirm_chargeDeclined_mapsErrorAndDispatchesWebhook() {
            MockPaymentIntent initialIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  REQUIRES_PAYMENT_METHOD
            );

            given(intentStore.transitionIfPending(eq(PAYMENT_INTENT_ID), transitionCaptor.capture()))
                  .willAnswer(invocation -> {
                      UnaryOperator<MockPaymentIntent> operator = invocation.getArgument(1);
                      MockPaymentIntent updated = operator.apply(initialIntent);
                      return new TransitionResult(updated, true);
                  });

            mockRestClientPostCall();

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_chargeDeclined");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo("requires_payment_method");
            assertThat(response.clientSecret()).isEqualTo(CLIENT_SECRET);

            then(requestBodySpec).should().body(payloadCaptor.capture());
            MockStripeWebhookPayload payload = payloadCaptor.getValue();

            assertThat(payload.type()).isEqualTo("payment_intent.payment_failed");
            assertThat(payload.data().object().lastPaymentError()).isNotNull();
            assertThat(payload.data().object().lastPaymentError().code()).isEqualTo("card_declined");
            assertThat(payload.data().object().lastPaymentError().message()).isEqualTo("Your card was declined.");
        }

        @Test
        @DisplayName("handles insufficient funds, maps insufficient_funds error, and sends payment_failed webhook")
        void confirm_insufficientFunds_mapsErrorAndDispatchesWebhook() {
            MockPaymentIntent initialIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  REQUIRES_PAYMENT_METHOD
            );

            given(intentStore.transitionIfPending(eq(PAYMENT_INTENT_ID), transitionCaptor.capture()))
                  .willAnswer(invocation -> {
                      UnaryOperator<MockPaymentIntent> operator = invocation.getArgument(1);
                      MockPaymentIntent updated = operator.apply(initialIntent);
                      return new TransitionResult(updated, true);
                  });

            mockRestClientPostCall();

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_insufficientFunds");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo("requires_payment_method");
            assertThat(response.clientSecret()).isEqualTo(CLIENT_SECRET);

            then(requestBodySpec).should().body(payloadCaptor.capture());
            MockStripeWebhookPayload payload = payloadCaptor.getValue();

            assertThat(payload.type()).isEqualTo("payment_intent.payment_failed");
            assertThat(payload.data().object().lastPaymentError()).isNotNull();
            assertThat(payload.data().object().lastPaymentError().code()).isEqualTo("insufficient_funds");
            assertThat(payload.data().object().lastPaymentError().message()).isEqualTo("Your card has insufficient funds.");
        }
    }

    @Nested
    @DisplayName("Idempotency and Non-Applied Transitions")
    class IdempotencyHandling {

        @Test
        @DisplayName("returns current intent state without triggering webhooks when transition was not applied")
        void confirm_transitionNotApplied_returnsCurrentStateWithoutWebhook() {
            MockPaymentIntent existingSucceededIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  SUCCEEDED
            );

            given(intentStore.transitionIfPending(eq(PAYMENT_INTENT_ID), any()))
                  .willReturn(new TransitionResult(existingSucceededIntent, false));

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_visa");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response.id()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.status()).isEqualTo("succeeded");
            assertThat(response.clientSecret()).isEqualTo(CLIENT_SECRET);

            verifyNoInteractions(restClient);
        }
    }

    @Nested
    @DisplayName("Webhook Delivery Retries")
    class WebhookRetries {

        @Test
        @DisplayName("retries webhook posting when RestClient throws exception")
        void postWebhook_transientError_retriesAndSucceeds() {
            MockPaymentIntent initialIntent = new MockPaymentIntent(
                  PAYMENT_INTENT_ID,
                  CLIENT_SECRET,
                  AMOUNT_CENTS,
                  REQUIRES_PAYMENT_METHOD
            );

            given(intentStore.transitionIfPending(eq(PAYMENT_INTENT_ID), transitionCaptor.capture()))
                  .willAnswer(invocation -> {
                      UnaryOperator<MockPaymentIntent> operator = invocation.getArgument(1);
                      MockPaymentIntent updated = operator.apply(initialIntent);
                      return new TransitionResult(updated, true);
                  });

            mockRestClientPostCall();

            doAnswer(invocation -> {
                throw new RuntimeException("Network connection reset");
            }).doAnswer(invocation -> null)
                  .when(responseSpec).toBodilessEntity();

            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(CLIENT_SECRET, "pm_card_visa");
            ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(PAYMENT_INTENT_ID, request);

            assertThat(response.status()).isEqualTo("succeeded");
            then(responseSpec).should(times(2)).toBodilessEntity();
        }
    }
}