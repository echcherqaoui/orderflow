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
import static org.mockito.ArgumentMatchers.any;
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
    private ArgumentCaptor<StripeWebhookPayload> payloadCaptor;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        void handleStripeWebhook_directInvocation_returns200OkWithEmptyBody() {
            StripeWebhookPayload payload = createValidPayload();
            willDoNothing().given(stripeWebhookService).processWebhook(any(StripeWebhookPayload.class));

            ResponseEntity<Void> response = stripeWebhookController.handleStripeWebhook(payload);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNull();

            then(stripeWebhookService).should().processWebhook(payloadCaptor.capture());
            assertThat(payloadCaptor.getValue()).isEqualTo(payload);
        }

        @Test
        @DisplayName("valid HTTP payload delegates to service and returns 200 OK")
        void handleStripeWebhook_validPayload_returns200Ok() throws Exception {
            StripeWebhookPayload payload = createValidPayload();
            willDoNothing().given(stripeWebhookService).processWebhook(any(StripeWebhookPayload.class));

            mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                  .andExpect(status().isOk());

            then(stripeWebhookService).should().processWebhook(payloadCaptor.capture());
            StripeWebhookPayload capturedPayload = payloadCaptor.getValue();
            assertThat(capturedPayload.id()).isEqualTo(validEventId);
            assertThat(capturedPayload.type()).isEqualTo(validEventType);
            assertThat(capturedPayload.data().object().id()).isEqualTo(validPaymentIntentId);
            assertThat(capturedPayload.data().object().amount()).isEqualTo(validAmountCents);
        }

        @Test
        @DisplayName("service failure propagates exception during execution")
        void handleStripeWebhook_serviceFails_propagatesException() {
            StripeWebhookPayload payload = createValidPayload();
            RuntimeException serviceException = new RuntimeException("Failed to process webhook transaction");
            willThrow(serviceException).given(stripeWebhookService).processWebhook(any(StripeWebhookPayload.class));

            assertThatThrownBy(() -> stripeWebhookController.handleStripeWebhook(payload))
                  .isSameAs(serviceException);

            then(stripeWebhookService).should().processWebhook(any(StripeWebhookPayload.class));
        }
    }

    private StripeWebhookPayload createValidPayload() {
        ObjectData objectData = new ObjectData(
              validPaymentIntentId,
              validAmountCents,
              "usd",
              "succeeded",
              null
        );
        return new StripeWebhookPayload(validEventId, validEventType, new Data(objectData));
    }
}