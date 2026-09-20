package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentRequest;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MockStripeControllerTest {

    @Mock
    private MockStripeService mockStripeService;

    @InjectMocks
    private MockStripeController mockStripeController;

    @Captor
    private ArgumentCaptor<ConfirmPaymentIntentRequest> requestCaptor;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String validPaymentIntentId = "pi_mock_123456";
    private final String validClientSecret = "secret_mock_123456";
    private final String validPaymentMethod = "pm_card_visa";

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(mockStripeController)
              .setValidator(validator)
              .build();
    }

    @Nested
    @DisplayName("confirmPaymentIntent()")
    class ConfirmPaymentIntent {

        @Test
        @DisplayName("direct method invocation returns 200 OK with response body from service")
        void confirmPaymentIntent_directInvocation_returns200OkWithResponse() {
            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(validClientSecret, validPaymentMethod);
            ConfirmPaymentIntentResponse expectedResponse = new ConfirmPaymentIntentResponse(validPaymentIntentId, "succeeded", validClientSecret);
            given(mockStripeService.confirmAndTriggerWebhook(eq(validPaymentIntentId), any(ConfirmPaymentIntentRequest.class)))
                  .willReturn(expectedResponse);

            ResponseEntity<ConfirmPaymentIntentResponse> response = mockStripeController.confirmPaymentIntent(validPaymentIntentId, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(expectedResponse);

            then(mockStripeService).should().confirmAndTriggerWebhook(eq(validPaymentIntentId), requestCaptor.capture());
            assertThat(requestCaptor.getValue()).isEqualTo(request);
        }

        @Test
        @DisplayName("valid HTTP payload passes validation, returning 200 OK with confirm response payload")
        void confirmPaymentIntent_validPayload_returns200OkAndResponseBody() throws Exception {
            ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(validClientSecret, validPaymentMethod);
            ConfirmPaymentIntentResponse expectedResponse = new ConfirmPaymentIntentResponse(validPaymentIntentId, "succeeded", validClientSecret);
            given(mockStripeService.confirmAndTriggerWebhook(eq(validPaymentIntentId), any(ConfirmPaymentIntentRequest.class)))
                  .willReturn(expectedResponse);

            mockMvc.perform(post("/mock-stripe/payment_intents/{id}/confirm", validPaymentIntentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                  ).andExpect(status().isOk())
                  .andExpect(jsonPath("$.id").value(validPaymentIntentId))
                  .andExpect(jsonPath("$.status").value("succeeded"))
                  .andExpect(jsonPath("$.clientSecret").value(validClientSecret));

            then(mockStripeService).should().confirmAndTriggerWebhook(eq(validPaymentIntentId), requestCaptor.capture());
            ConfirmPaymentIntentRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.clientSecret()).isEqualTo(validClientSecret);
            assertThat(capturedRequest.paymentMethod()).isEqualTo(validPaymentMethod);
        }


        private static Stream<Arguments> invalidRequestProvider() {
            return Stream.of(
                  Arguments.of("blank clientSecret", new ConfirmPaymentIntentRequest("", "pm_card_visa")),
                  Arguments.of("null clientSecret", new ConfirmPaymentIntentRequest(null, "pm_card_visa")),
                  Arguments.of("blank paymentMethod", new ConfirmPaymentIntentRequest("secret_mock_123456", "")),
                  Arguments.of("null paymentMethod", new ConfirmPaymentIntentRequest("secret_mock_123456", null))
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRequestProvider")
        @DisplayName("invalid payload violates validation constraints and returns 400 Bad Request")
        void confirmPaymentIntent_invalidPayload_returns400BadRequest(String testName, ConfirmPaymentIntentRequest invalidRequest) throws Exception {
            mockMvc.perform(post("/mock-stripe/payment_intents/{id}/confirm", validPaymentIntentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest))
                  ).andExpect(status().isBadRequest());

            verifyNoInteractions(mockStripeService);
        }
    }
}