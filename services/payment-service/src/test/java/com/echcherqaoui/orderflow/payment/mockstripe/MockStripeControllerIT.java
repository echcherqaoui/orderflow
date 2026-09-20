package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentRequest;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles
class MockStripeControllerIT implements WithPostgres {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockPaymentIntentStore intentStore;

    private String idempotencyKey;
    private long totalAmountCents;

    @BeforeEach
    void setUp() {
        idempotencyKey = "idem_" + UUID.randomUUID();
        totalAmountCents = 2500L;
    }

    @Test
    @DisplayName("valid payment confirmation returns 200 OK with confirmed status")
    void confirmPaymentIntent_validRequest_returns200Ok() throws Exception {
        MockPaymentIntent intent = intentStore.computeIfAbsent(idempotencyKey, totalAmountCents);

        ConfirmPaymentIntentRequest request = new ConfirmPaymentIntentRequest(intent.clientSecret(), "pm_card_visa");

        mockMvc.perform(post("/mock-stripe/payment_intents/{id}/confirm", intent.paymentIntentId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
              ).andExpect(status().isOk())
              .andExpect(jsonPath("$.id").value(intent.paymentIntentId()))
              .andExpect(jsonPath("$.status").isNotEmpty());
    }

    @Test
    @DisplayName("invalid payload fails DTO validation, returns 400 Bad Request")
    void confirmPaymentIntent_invalidRequestBody_returns400() throws Exception {
        MockPaymentIntent intent = intentStore.computeIfAbsent(idempotencyKey, totalAmountCents);

        // Blank clientSecret or invalid fields depending on your ConfirmPaymentIntentRequest validation rules
        ConfirmPaymentIntentRequest invalidRequest = new ConfirmPaymentIntentRequest("", "");

        mockMvc.perform(post("/mock-stripe/payment_intents/{id}/confirm", intent.paymentIntentId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)))
              .andExpect(status().isBadRequest());
    }
}