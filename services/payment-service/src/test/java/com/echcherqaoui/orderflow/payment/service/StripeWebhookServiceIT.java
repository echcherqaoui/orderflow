package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.Data;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.ObjectData;
import com.echcherqaoui.orderflow.payment.dto.StripeWebhookPayload.PaymentError;
import com.echcherqaoui.orderflow.payment.exception.domain.InvalidWebhookSignatureException;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentAttemptStatus;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import com.echcherqaoui.orderflow.payment.repository.ProcessedWebhookEventRepository;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
class StripeWebhookServiceIT implements WithPostgres {

    @Autowired
    private StripeWebhookService stripeWebhookService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StripeWebhookSignatureVerifier webhookSignatureVerifier;

    private final String signature = "t=12345,v1=valid_signature_hash";
    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-123";
    private final long totalAmountCents = 25000L;
    private final String eventId = "evt_stripe_" + UUID.randomUUID();
    private final String paymentIntentId = "pi_stripe_" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        processedWebhookEventRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();

        given(webhookSignatureVerifier.verify(anyString(), anyString())).willReturn(true);
    }

    private Payment savePendingPayment() {
        Payment initialPayment = new Payment()
              .setOrderId(orderId)
              .setPaymentIntentId(paymentIntentId)
              .setUserId(userId)
              .setTotalAmountCents(totalAmountCents)
              .setStatus(PaymentStatus.PENDING);
        return paymentRepository.saveAndFlush(initialPayment);
    }


    /**
     * Loads payment and initializes its lazy attempts inside the transaction.
     */
    private Payment paymentWithAttempts() {
        return txTemplate.execute(status -> {
            Payment payment = paymentRepository.findAll().getFirst();
            Hibernate.initialize(payment.getAttempts());
            return payment;
        });
    }

    @Nested
    @DisplayName("processWebhook()")
    class ProcessWebhook {

        @Test
        @DisplayName("null signature throws NullPointerException without touching database")
        void processWebhook_nullSignature_throwsNullPointerException() {
            assertThatThrownBy(() -> stripeWebhookService.processWebhook(null, "{}"))
                  .isInstanceOf(NullPointerException.class);

            assertThat(processedWebhookEventRepository.findAll()).isEmpty();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("null rawPayload throws NullPointerException without touching database")
        void processWebhook_nullRawPayload_throwsNullPointerException() {
            assertThatThrownBy(() -> stripeWebhookService.processWebhook(signature, null))
                  .isInstanceOf(NullPointerException.class);

            assertThat(processedWebhookEventRepository.findAll()).isEmpty();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("invalid webhook signature throws InvalidWebhookSignatureException")
        void processWebhook_invalidSignature_throwsInvalidWebhookSignatureException() {
            given(webhookSignatureVerifier.verify(anyString(), anyString())).willReturn(false);

            assertThatThrownBy(() -> stripeWebhookService.processWebhook(signature, "{\"id\":\"evt_123\"}"))
                  .isInstanceOf(InvalidWebhookSignatureException.class);

            assertThat(processedWebhookEventRepository.findAll()).isEmpty();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("malformed JSON rawPayload throws IllegalArgumentException")
        void processWebhook_malformedJson_throwsIllegalArgumentException() {
            String malformedJson = "{ invalid_json }";

            assertThatThrownBy(() -> stripeWebhookService.processWebhook(signature, malformedJson))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Invalid payload format");

            assertThat(processedWebhookEventRepository.findAll()).isEmpty();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("payment_intent.succeeded marks payment SUCCESS and emits outbox event atomically")
        void processWebhook_paymentIntentSucceeded_marksSuccessAndPersistsOutbox() throws Exception {
            savePendingPayment();

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));
            String rawPayload = objectMapper.writeValueAsString(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

            Payment updatedPayment = paymentRepository.findAll().getFirst();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentChargedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("payment_intent.payment_failed records failed attempt and failure reason without changing status or emitting outbox event")
        void processWebhook_paymentIntentFailed_withError_recordsAttemptAndLeavesPaymentPending() throws Exception {
            savePendingPayment();

            PaymentError error = new PaymentError("card_declined", "Card was declined");
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "failed", error);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", new Data(objectData));
            String rawPayload = objectMapper.writeValueAsString(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

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
        @DisplayName("payment_intent.payment_failed without last_payment_error records attempt with default reason")
        void processWebhook_paymentIntentFailed_withoutError_recordsAttemptWithDefaultReason() throws Exception {
            savePendingPayment();

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "failed", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", new Data(objectData));
            String rawPayload = objectMapper.writeValueAsString(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            Payment updatedPayment = paymentWithAttempts();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(updatedPayment.getFailureReason()).isEqualTo("Payment authorization failed");
            assertThat(updatedPayment.getAttempts()).hasSize(1);

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("payment_intent.canceled marks payment FAILED and emits outbox event")
        void processWebhook_paymentIntentCanceled_marksFailedAndPersistsOutbox() throws Exception {
            savePendingPayment();

            PaymentError error = new PaymentError("abandoned", "Customer canceled payment");
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "canceled", error);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.canceled", new Data(objectData));
            String rawPayload = objectMapper.writeValueAsString(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

            Payment updatedPayment = paymentRepository.findAll().getFirst();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(updatedPayment.getFailureReason()).isEqualTo("Customer canceled payment");

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentFailedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("duplicate webhook delivery catches unique constraint and exits gracefully without re-processing")
        void processWebhook_duplicateEvent_swallowsDataIntegrityExceptionAndExitsCleanly() throws Exception {
            savePendingPayment();

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));
            String rawPayload = objectMapper.writeValueAsString(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);
            stripeWebhookService.processWebhook(signature, rawPayload);

            assertThat(processedWebhookEventRepository.findAll()).hasSize(1);
            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("concurrent webhook deliveries execute idempotency lock and process state change exactly once")
        void processWebhook_concurrentRequests_guaranteesSingleExecution() throws Exception {
            savePendingPayment();

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));
            String rawPayload = objectMapper.writeValueAsString(payload);

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CyclicBarrier barrier = new CyclicBarrier(threadCount);

            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        stripeWebhookService.processWebhook(signature, rawPayload);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (BrokenBarrierException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get();
            }

            executor.shutdown();

            assertThat(processedWebhookEventRepository.findAll()).hasSize(1);
            assertThat(paymentRepository.findAll().getFirst().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("unhandled webhook event registers idempotency and exits cleanly without state changes")
        void processWebhook_unhandledEventType_registersIdempotencyAndSkipsProcessing() throws Exception {
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "active", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "customer.created", new Data(objectData));
            String rawPayload = objectMapper.writeValueAsString(payload);

            stripeWebhookService.processWebhook(signature, rawPayload);

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }
    }
}