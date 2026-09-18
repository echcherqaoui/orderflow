package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
    }

    @Nested
    @DisplayName("processWebhook()")
    class ProcessWebhook {

        @Test
        @DisplayName("payment_intent.succeeded marks payment CHARGED and emits outbox event atomically")
        void processWebhook_paymentIntentSucceeded_marksChargedAndPersistsOutbox() {
            Payment initialPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(initialPayment);

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));

            stripeWebhookService.processWebhook(payload);

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
        @DisplayName("payment_intent.payment_failed with last_payment_error marks payment FAILED and emits outbox event")
        void processWebhook_paymentIntentFailed_withError_marksFailedAndPersistsOutbox() {
            Payment initialPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(initialPayment);

            PaymentError error = new PaymentError("card_declined", "Card was declined");
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "failed", error);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", new Data(objectData));

            stripeWebhookService.processWebhook(payload);

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

            Payment updatedPayment = paymentRepository.findAll().getFirst();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(updatedPayment.getFailureReason()).isEqualTo("Card was declined");

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentFailedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("payment_intent.payment_failed without last_payment_error falls back to default reason")
        void processWebhook_paymentIntentFailed_withoutError_marksFailedWithDefaultReason() {
            Payment initialPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(initialPayment);

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "failed", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.payment_failed", new Data(objectData));

            stripeWebhookService.processWebhook(payload);

            Payment updatedPayment = paymentRepository.findAll().getFirst();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(updatedPayment.getFailureReason()).isEqualTo("Payment authorization failed");
        }

        @Test
        @DisplayName("duplicate webhook delivery catches unique constraint and exits gracefully without re-processing")
        void processWebhook_duplicateEvent_swallowsDataIntegrityExceptionAndExitsCleanly() {
            Payment initialPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(initialPayment);

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));

            stripeWebhookService.processWebhook(payload);
            stripeWebhookService.processWebhook(payload);

            assertThat(processedWebhookEventRepository.findAll()).hasSize(1);
            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("concurrent webhook deliveries execute idempotency lock and process state change exactly once")
        void processWebhook_concurrentRequests_guaranteesSingleExecution() throws Exception {
            Payment initialPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(initialPayment);

            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "succeeded", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "payment_intent.succeeded", new Data(objectData));

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CyclicBarrier barrier = new CyclicBarrier(threadCount);

            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        stripeWebhookService.processWebhook(payload);
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
        void processWebhook_unhandledEventType_registersIdempotencyAndSkipsProcessing() {
            ObjectData objectData = new ObjectData(paymentIntentId, totalAmountCents, "usd", "active", null);
            StripeWebhookPayload payload = new StripeWebhookPayload(eventId, "customer.created", new Data(objectData));

            stripeWebhookService.processWebhook(payload);

            assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("null payload throws NullPointerException")
        void processWebhook_nullPayload_throwsNullPointerException() {
            assertThatThrownBy(() -> stripeWebhookService.processWebhook(null))
                  .isInstanceOf(NullPointerException.class);

            assertThat(processedWebhookEventRepository.findAll()).isEmpty();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }
    }
}