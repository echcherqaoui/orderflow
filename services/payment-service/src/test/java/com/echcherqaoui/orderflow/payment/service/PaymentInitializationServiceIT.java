package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.payment.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class PaymentInitializationServiceIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentInitializationService paymentInitializationService;

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

            paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

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

            paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            verifyNoInteractions(paymentGateway);

            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("concurrent executions trigger TOCTOU race condition but database unique constraint prevents duplicate persistence")
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
                        paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (BrokenBarrierException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            int constraintViolations = 0;
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof DataIntegrityViolationException)
                        constraintViolations++;
                    else
                        throw e;
                }
            }

            executor.shutdown();

            assertThat(constraintViolations).isEqualTo(1);
            assertThat(paymentRepository.findAll()).hasSize(1);
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("psp gateway failure prevents database persistence and throws exception")
        void initializePayment_gatewayFailure_abortsAndDoesNotPersist() {
            given(paymentGateway.createIntent(anyString(), anyLong()))
                  .willThrow(new RuntimeException("PSP connection timeout"));

            assertThatThrownBy(() ->
                  paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId)
            ).isInstanceOf(RuntimeException.class)
             .hasMessage("PSP connection timeout");

            assertThat(paymentRepository.existsByOrderId(orderId)).isFalse();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void initializePayment_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  paymentInitializationService.initializePayment(null, userId, totalAmountCents, triggerEventId)
            ).isInstanceOf(NullPointerException.class);

            verifyNoInteractions(paymentGateway);
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("null userId throws NullPointerException")
        void initializePayment_nullUserId_throwsNullPointerException() {
            assertThatThrownBy(() ->
                  paymentInitializationService.initializePayment(orderId, null, totalAmountCents, triggerEventId)
            ).isInstanceOf(NullPointerException.class);

            verifyNoInteractions(paymentGateway);
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }
    }
}