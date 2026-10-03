package com.echcherqaoui.orderflow.payment.controller;

import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.PaymentError;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentAttemptStatus;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import com.echcherqaoui.orderflow.payment.repository.ProcessedWebhookEventRepository;
import com.echcherqaoui.orderflow.payment.service.StripeWebhookSignatureVerifier;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
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

    @Autowired
    private TransactionTemplate txTemplate;

    @MockitoBean
    private StripeWebhookSignatureVerifier webhookSignatureVerifier;

    private final String validSignature = "t=12345,v1=valid_signature_hash";
    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-123";
    private final long totalAmountCents = 25000L;
    private final String paymentIntentId = "pi_stripe_" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        processedWebhookEventRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();

        // Ensure signature verification passes regardless of parameter evaluation order
        given(webhookSignatureVerifier.verify(anyString(), anyString())).willReturn(true);
    }

    private void savePendingPayment() {
        Payment initialPayment = new Payment()
              .setOrderId(orderId)
              .setPaymentIntentId(paymentIntentId)
              .setUserId(userId)
              .setTotalAmountCents(totalAmountCents)
              .setStatus(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(initialPayment);
    }

    private Payment paymentWithAttempts() {
        return txTemplate.execute(status -> {
            Payment updatedPayment = paymentRepository.findByPaymentIntentId(paymentIntentId).orElseThrow();

            Hibernate.initialize(updatedPayment.getAttempts());
            return updatedPayment;
        });
    }


    @Nested
    @DisplayName("handleStripeWebhook()")
    class HandleStripeWebhook {

        @Test
        @DisplayName("valid payment_intent.succeeded webhook returns 200 OK and updates payment state")
        void handleStripeWebhook_succeededEvent_returns200OkAndProcessesPayment() throws Exception {
            savePendingPayment();

            String eventId = "evt_stripe_" + UUID.randomUUID();
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));

            mockMvc.perform(post("/webhooks/stripe")
                  .header("Stripe-Signature", validSignature)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(payload))
            ).andExpect(status().isOk());

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

            Payment updatedPayment = paymentRepository.findByPaymentIntentId(paymentIntentId).orElseThrow();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("valid payment_intent.payment_failed webhook returns 200 OK, records failed attempt, leaves status PENDING, and skips outbox")
        void handleStripeWebhook_failedEvent_returns200OkAndRecordsFailedAttempt() throws Exception {
            savePendingPayment();

            String eventId = "evt_stripe_" + UUID.randomUUID();
            PaymentError error = new PaymentError("card_declined", "Card was declined");
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "failed", error);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", new Data(objectData));

            mockMvc.perform(post("/webhooks/stripe")
                  .header("Stripe-Signature", validSignature)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(payload))
            ).andExpect(status().isOk());

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

            Payment updatedPayment = paymentWithAttempts();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(updatedPayment.getFailureReason()).isEqualTo("Card was declined");
            assertThat(updatedPayment.getAttempts()).hasSize(1);
            assertThat(updatedPayment.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(updatedPayment.getAttempts().getFirst().getErrorCode()).isEqualTo("card_declined");

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("duplicate webhook event returns 200 OK without double-processing")
        void handleStripeWebhook_duplicateEvent_returns200OkAndIgnoresDuplicate() throws Exception {
            savePendingPayment();

            String eventId = "evt_stripe_" + UUID.randomUUID();
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));

            mockMvc.perform(post("/webhooks/stripe")
                  .header("Stripe-Signature", validSignature)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(payload))
            ).andExpect(status().isOk());

            mockMvc.perform(post("/webhooks/stripe")
                  .header("Stripe-Signature", validSignature)
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
                  .header("Stripe-Signature", validSignature)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(payload))
            ).andExpect(status().isOk());

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }
    }
}