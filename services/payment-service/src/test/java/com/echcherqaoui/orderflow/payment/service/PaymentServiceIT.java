package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceIT implements WithPostgres {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-123";
    private final long totalAmountCents = 25000L;
    private final String triggerEventId = "evt-" + UUID.randomUUID();
    private final CreatePaymentIntentResponse pspResponse =
          new CreatePaymentIntentResponse("pi_stripe_12345", "secret_stripe_12345");

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Nested
    @DisplayName("initializePayment()")
    class InitializePayment {

        @Test
        @DisplayName("successfully creates payment intent via gateway and persists payment and outbox atomically")
        void initializePayment_success_createsIntentAndPersistsState() {
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents))
                  .willReturn(pspResponse);

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            verify(paymentGateway).createIntent(orderId.toString(), totalAmountCents);

            assertThat(paymentRepository.existsByOrderId(orderId)).isTrue();

            List<Payment> payments = paymentRepository.findAll();
            assertThat(payments).hasSize(1);

            Payment payment = payments.getFirst();
            assertThat(payment.getOrderId()).isEqualTo(orderId);
            assertThat(payment.getPaymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
            assertThat(payment.getUserId()).isEqualTo(userId);
            assertThat(payment.getTotalAmountCents()).isEqualTo(totalAmountCents);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentInitiatedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("idempotency check skips gateway call and persistence when payment session already exists")
        void initializePayment_alreadyInitialized_skipsProcessing() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId("pi_existing_999")
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(existingPayment);

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            verifyNoInteractions(paymentGateway);

            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("concurrent executions trigger TOCTOU race condition but DB unique constraint is caught gracefully")
        void initializePayment_concurrentRequests_guaranteesSingleDatabasePersistence() throws Exception {
            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CyclicBarrier barrier = new CyclicBarrier(threadCount);

            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents))
                  .willReturn(pspResponse);

            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);
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

            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("psp gateway failure persists failed payment status and failure outbox event atomically")
        void initializePayment_gatewayFailure_persistsFailedPaymentAndOutbox() {
            given(paymentGateway.createIntent(anyString(), anyLong()))
                  .willThrow(new RuntimeException("PSP connection timeout"));

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            assertThat(paymentRepository.existsByOrderId(orderId)).isTrue();

            List<Payment> payments = paymentRepository.findAll();
            assertThat(payments).hasSize(1);

            Payment payment = payments.getFirst();
            assertThat(payment.getOrderId()).isEqualTo(orderId);
            assertThat(payment.getPaymentIntentId()).isNull();
            assertThat(payment.getUserId()).isEqualTo(userId);
            assertThat(payment.getTotalAmountCents()).isEqualTo(totalAmountCents);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("PSP_GATEWAY_UNAVAILABLE");

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentInitializationFailedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("psp gateway transient failure persists PSP_PAYMENT_DECLINED reason code")
        void initializePayment_transientGatewayFailure_persistsPaymentDeclined() {
            given(paymentGateway.createIntent(anyString(), anyLong()))
                  .willThrow(new PaymentGatewayTransientException(new RuntimeException("Card declined")));

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            Payment payment = paymentRepository.findAll().getFirst();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("PSP_PAYMENT_DECLINED");
        }

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void initializePayment_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  paymentService.initializePayment(null, userId, totalAmountCents, triggerEventId)
            ).isInstanceOf(NullPointerException.class);

            verifyNoInteractions(paymentGateway);
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("null userId throws NullPointerException")
        void initializePayment_nullUserId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  paymentService.initializePayment(orderId, null, totalAmountCents, triggerEventId)
            ).isInstanceOf(NullPointerException.class);

            verifyNoInteractions(paymentGateway);
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }
    }

    @Nested
    @DisplayName("cancelPayment()")
    class CancelPayment {

        @Test
        @DisplayName("skips gateway call and persistence when payment record does not exist")
        void cancelPayment_paymentNotFound_skipsProcessing() {
            paymentService.cancelPayment(orderId, "pi_stripe_12345", "Customer request", triggerEventId);

            verifyNoInteractions(paymentGateway);
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("skips gateway call and persistence when payment is already CANCELLED")
        void cancelPayment_alreadyCancelled_skipsProcessing() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId("pi_stripe_12345")
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.CANCELLED);
            paymentRepository.saveAndFlush(existingPayment);

            paymentService.cancelPayment(orderId, "pi_stripe_12345", "Customer request", triggerEventId);

            verifyNoInteractions(paymentGateway);
            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("successfully cancels intent on PSP and updates local DB state with explicit intent ID and reason")
        void cancelPayment_success_usesExplicitParamsAndCancels() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId("pi_stripe_initial")
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(existingPayment);

            String explicitIntentId = "pi_stripe_override";
            String customReason = "Order items out of stock";

            paymentService.cancelPayment(orderId, explicitIntentId, customReason, triggerEventId);

            verify(paymentGateway).cancelIntent(explicitIntentId);

            Payment updatedPayment = paymentRepository.findAll().getFirst();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentCancelledEvent");
        }

        @Test
        @DisplayName("falls back to DB intent ID and default reason when nullable parameters are omitted")
        void cancelPayment_nullParams_fallsBackToDbDefaults() {
            String dbIntentId = "pi_stripe_from_db";
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(dbIntentId)
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(existingPayment);

            paymentService.cancelPayment(orderId, null, null, triggerEventId);

            verify(paymentGateway).cancelIntent(dbIntentId);

            Payment updatedPayment = paymentRepository.findAll().getFirst();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);
        }

        @Test
        @DisplayName("continues local saga compensation and persists cancellation even if PSP gateway call fails")
        void cancelPayment_gatewayException_swallowsErrorAndProceedsWithLocalCancellation() {
            String dbIntentId = "pi_stripe_12345";
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(dbIntentId)
                  .setUserId(userId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);
            paymentRepository.saveAndFlush(existingPayment);

            willThrow(new RuntimeException("PSP connection error during cancellation"))
                  .given(paymentGateway).cancelIntent(dbIntentId);

            paymentService.cancelPayment(orderId, null, "Saga compensation", triggerEventId);

            verify(paymentGateway).cancelIntent(dbIntentId);

            Payment updatedPayment = paymentRepository.findAll().getFirst();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void cancelPayment_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  paymentService.cancelPayment(null, "pi_stripe_12345", "Reason", triggerEventId)
            ).isInstanceOf(NullPointerException.class);

            verifyNoInteractions(paymentGateway);
        }
    }
}