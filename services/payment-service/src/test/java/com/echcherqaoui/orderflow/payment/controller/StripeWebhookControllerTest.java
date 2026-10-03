package com.echcherqaoui.orderflow.payment.controller;

import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.service.StripeWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StripeWebhookControllerTest {

    @Mock
    private StripeWebhookService stripeWebhookService;

    @InjectMocks
    private StripeWebhookController stripeWebhookController;

    @Captor
    private ArgumentCaptor<String> signatureCaptor;

    @Captor
    private ArgumentCaptor<String> payloadCaptor;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String validSignature = "t=12345,v1=valid_signature_hash";
    private final String validEventId = "evt_stripe_" + UUID.randomUUID();
    private final String validEventType = "payment_intent.succeeded";
    private final String validPaymentIntentId = "pi_stripe_" + UUID.randomUUID();
    private final long validAmountCents = 25000L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(stripeWebhookController)
              .build();
    }

    @Nested
    @DisplayName("handleStripeWebhook()")
    class HandleStripeWebhook {

        @Test
        @DisplayName("direct method invocation returns 200 OK with empty body from service")
        void handleStripeWebhook_directInvocation_returns200OkWithEmptyBody() throws Exception {
            String rawPayload = createValidPayloadJson();
            willDoNothing().given(stripeWebhookService).processWebhook(anyString(), anyString());

            ResponseEntity<Void> response = stripeWebhookController.handleStripeWebhook(validSignature, rawPayload);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNull();

            then(stripeWebhookService).should().processWebhook(signatureCaptor.capture(), payloadCaptor.capture());
            assertThat(signatureCaptor.getValue()).isEqualTo(validSignature);
            assertThat(payloadCaptor.getValue()).isEqualTo(rawPayload);
        }

        @Test
        @DisplayName("valid HTTP payload and signature delegate to service and return 200 OK")
        void handleStripeWebhook_validPayload_returns200Ok() throws Exception {
            String rawPayload = createValidPayloadJson();
            willDoNothing().given(stripeWebhookService).processWebhook(anyString(), anyString());

            mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", validSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawPayload))
                  .andExpect(status().isOk());

            then(stripeWebhookService).should().processWebhook(signatureCaptor.capture(), payloadCaptor.capture());
            assertThat(signatureCaptor.getValue()).isEqualTo(validSignature);
            assertThat(payloadCaptor.getValue()).isEqualTo(rawPayload);
        }

        @Test
        @DisplayName("service failure propagates exception during execution")
        void handleStripeWebhook_serviceFails_propagatesException() throws Exception {
            String rawPayload = createValidPayloadJson();
            RuntimeException serviceException = new RuntimeException("Failed to process webhook transaction");
            willThrow(serviceException).given(stripeWebhookService).processWebhook(anyString(), anyString());

            assertThatThrownBy(() -> stripeWebhookController.handleStripeWebhook(validSignature, rawPayload))
                  .isSameAs(serviceException);

            then(stripeWebhookService).should().processWebhook(validSignature, rawPayload);
        }
    }

    private String createValidPayloadJson() throws Exception {
        ObjectData objectData = new ObjectData(
              validPaymentIntentId,
              validAmountCents,
              "usd",
              "succeeded",
              null
        );
        StripeWebhookPayload payload = new StripeWebhookPayload(validEventId, validEventType, new Data(objectData));
        return objectMapper.writeValueAsString(payload);
    }
}