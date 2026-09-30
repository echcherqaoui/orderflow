package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.RejectedExecutionException;

import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.echcherqaoui.orderflow.payment.mockstripe.MockPaymentIntentStatus.SUCCEEDED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MockStripeWebhookDispatcherTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Mock
    private MockPspProperties mockPspProperties;

    @Mock
    private Executor webhookDeliveryExecutor;

    @Captor
    private ArgumentCaptor<byte[]> bodyCaptor;

    @Captor
    private ArgumentCaptor<String> sigHeaderCaptor;

    private MockStripeWebhookDispatcher dispatcher;

    private static final String PAYMENT_INTENT_ID = "pi_mock_123456";
    private static final String WEBHOOK_URL = "http://localhost:8080/api/webhooks/stripe";
    private static final String WEBHOOK_SECRET = "whsec_test_secret_12345";
    private static final long AMOUNT_CENTS = 5000L;

    @BeforeEach
    void setUp() {
        dispatcher = new MockStripeWebhookDispatcher(
              restClient,
              webhookDeliveryExecutor,
              mockPspProperties,
              new ObjectMapper()
        );
    }

    private void mockRestClientPipeline() {
        given(mockPspProperties.getWebhookUrl()).willReturn(WEBHOOK_URL);
        given(mockPspProperties.getWebhookSecret()).willReturn(WEBHOOK_SECRET);

        given(restClient.post()).willReturn(requestBodyUriSpec);
        given(requestBodyUriSpec.uri(WEBHOOK_URL)).willReturn(requestBodySpec);
        given(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).willReturn(requestBodySpec);
        given(requestBodySpec.body(any(byte[].class))).willReturn(requestBodySpec);
        given(requestBodySpec.header(eq("Stripe-Signature"), any())).willReturn(requestBodySpec);
        given(requestBodySpec.retrieve()).willReturn(responseSpec);
    }

    @Nested
    @DisplayName("hmacHex Utility")
    class HmacHexUtility {

        @Test
        @DisplayName("computes correct HMAC-SHA256 hex string given standard inputs")
        void hmacHex_validInput_returnsExpectedHex() {
            String secret = "secret_key";
            byte[] part1 = "timestamp.".getBytes(UTF_8);
            byte[] part2 = "{\"data\":\"test\"}".getBytes(UTF_8);

            String result = MockStripeWebhookDispatcher.hmacHex(secret, part1, part2);

            assertThat(result)
                  .isNotNull()
                  .matches("^[a-f0-9]{64}$");
        }
    }

    @Nested
    @DisplayName("Successful Webhook Dispatch")
    class SuccessfulDispatch {

        @Test
        @DisplayName("serializes payload, calculates signature header, and posts webhook via RestClient")
        void dispatchWebhookAsync_success_postsExpectedBodyAndSignature() {
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(webhookDeliveryExecutor).execute(any(Runnable.class));

            mockRestClientPipeline();

            dispatcher.dispatchWebhookAsync(PAYMENT_INTENT_ID, AMOUNT_CENTS, SUCCEEDED, null, 1);

            then(restClient).should().post();
            then(requestBodyUriSpec).should().uri(WEBHOOK_URL);
            then(requestBodySpec).should().contentType(MediaType.APPLICATION_JSON);
            then(requestBodySpec).should().body(bodyCaptor.capture());
            then(requestBodySpec).should().header(eq("Stripe-Signature"), sigHeaderCaptor.capture());
            then(responseSpec).should().toBodilessEntity();

            byte[] postedBody = bodyCaptor.getValue();
            assertThat(new String(postedBody, UTF_8))
                  .contains("payment_intent.succeeded")
                  .contains(PAYMENT_INTENT_ID)
                  .contains("5000");

            String sigHeader = sigHeaderCaptor.getValue();
            assertThat(sigHeader).startsWith("t=").contains(",v1=");

            String[] parts = sigHeader.split(",v1=");
            long timestamp = Long.parseLong(parts[0].substring(2));
            String signature = parts[1];

            byte[] prefixBytes = (timestamp + ".").getBytes(UTF_8);
            String expectedSig = MockStripeWebhookDispatcher.hmacHex(WEBHOOK_SECRET, prefixBytes, postedBody);

            assertThat(signature).isEqualTo(expectedSig);
        }
    }

    @Nested
    @DisplayName("Retry and Error Handling")
    class RetryAndErrorHandling {

        @Test
        @DisplayName("retries webhook delivery up to 3 attempts upon RestClient errors")
        void dispatchWebhookAsync_transientFailures_retriesMaxAttempts() {
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(webhookDeliveryExecutor).execute(any(Runnable.class));

            mockRestClientPipeline();

            doThrow(new RuntimeException("Connection refused"))
                  .when(responseSpec).toBodilessEntity();

            dispatcher.dispatchWebhookAsync(PAYMENT_INTENT_ID, AMOUNT_CENTS, REQUIRES_PAYMENT_METHOD, null, 1);

            then(responseSpec).should(times(3)).toBodilessEntity();
        }

        @Test
        @DisplayName("gracefully handles JsonProcessingException during serialization without submitting to executor")
        void dispatchWebhookAsync_serializationError_abortsEarly() throws JsonProcessingException {
            ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
            dispatcher = new MockStripeWebhookDispatcher(
                  restClient,
                  webhookDeliveryExecutor,
                  mockPspProperties,
                  failingMapper
            );

            given(failingMapper.writeValueAsBytes(any())).willThrow(new JsonProcessingException("Serialization failed") {});

            dispatcher.dispatchWebhookAsync(PAYMENT_INTENT_ID, AMOUNT_CENTS, SUCCEEDED, null, 1);

            verifyNoInteractions(webhookDeliveryExecutor);
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("handles RejectedExecutionException gracefully when thread pool executor is saturated")
        void dispatchWebhookAsync_executorRejected_logsAndDoesNotThrow() {
            doThrow(new RejectedExecutionException("Executor queue full"))
                  .when(webhookDeliveryExecutor).execute(any(Runnable.class));

            assertThatNoException().isThrownBy(() ->
                  dispatcher.dispatchWebhookAsync(PAYMENT_INTENT_ID, AMOUNT_CENTS, SUCCEEDED, null, 1)
            );

            verifyNoInteractions(restClient);
        }
    }
}