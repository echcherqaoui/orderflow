package com.echcherqaoui.orderflow.payment.controller;

import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.PaymentError;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import com.echcherqaoui.orderflow.payment.repository.ProcessedWebhookEventRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StripeWebhookControllerIT implements WithPostgres {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-123";
    private final long totalAmountCents = 25000L;
    private final String paymentIntentId = "pi_stripe_" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        processedWebhookEventRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("valid payment_intent.succeeded webhook returns 200 OK and updates payment state")
    void handleStripeWebhook_succeededEvent_returns200OkAndProcessesPayment() throws Exception {
        Payment initialPayment = new Payment()
              .setOrderId(orderId)
              .setPaymentIntentId(paymentIntentId)
              .setUserId(userId)
              .setTotalAmountCents(totalAmountCents)
              .setStatus(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(initialPayment);

        String eventId = "evt_stripe_" + UUID.randomUUID();
        ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
        StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));

        mockMvc.perform(post("/webhooks/stripe")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload))
        ).andExpect(status().isOk());

        assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

        Payment updatedPayment = paymentRepository.findAll().getFirst();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("valid payment_intent.payment_failed webhook returns 200 OK and marks payment as FAILED")
    void handleStripeWebhook_failedEvent_returns200OkAndMarksPaymentFailed() throws Exception {
        Payment initialPayment = new Payment()
              .setOrderId(orderId)
              .setPaymentIntentId(paymentIntentId)
              .setUserId(userId)
              .setTotalAmountCents(totalAmountCents)
              .setStatus(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(initialPayment);

        String eventId = "evt_stripe_" + UUID.randomUUID();
        PaymentError error = new PaymentError("card_declined", "Card was declined");
        ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "failed", error);
        StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", new Data(objectData));

        mockMvc.perform(post("/webhooks/stripe")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload))
        ).andExpect(status().isOk());

        assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

        Payment updatedPayment = paymentRepository.findAll().getFirst();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(updatedPayment.getFailureReason()).isEqualTo("Card was declined");
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("duplicate webhook event returns 200 OK without double-processing")
    void handleStripeWebhook_duplicateEvent_returns200OkAndIgnoresDuplicate() throws Exception {
        Payment initialPayment = new Payment()
              .setOrderId(orderId)
              .setPaymentIntentId(paymentIntentId)
              .setUserId(userId)
              .setTotalAmountCents(totalAmountCents)
              .setStatus(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(initialPayment);

        String eventId = "evt_stripe_" + UUID.randomUUID();
        ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
        StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));

        mockMvc.perform(post("/webhooks/stripe")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload))
        ).andExpect(status().isOk());

        mockMvc.perform(post("/webhooks/stripe")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload))
        ).andExpect(status().isOk());

        assertThat(processedWebhookEventRepository.findAll()).hasSize(1);
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("unhandled webhook event returns 200 OK and registers idempotency record")
    void handleStripeWebhook_unhandledEventType_returns200OkAndSkips() throws Exception {
        String eventId = "evt_stripe_" + UUID.randomUUID();
        ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "active", null);
        StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "customer.created", new Data(objectData));

        mockMvc.perform(post("/webhooks/stripe")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload))
        ).andExpect(status().isOk());

        assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();
        assertThat(paymentRepository.findAll()).isEmpty();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }
}