package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionalWriterTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private PaymentTransactionalWriter paymentTransactionalWriter;

    @Captor
    private ArgumentCaptor<Payment> paymentCaptor;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-789";
    private final long totalAmountCents = 12500L;
    private final String triggerEventId = "evt-" + UUID.randomUUID();

    private CreatePaymentIntentResponse pspResponse;

    @BeforeEach
    void setUp() {
        pspResponse = new CreatePaymentIntentResponse("pi_123456", "requires_payment_method");
    }

    @Nested
    @DisplayName("savePaymentAndOutbox()")
    class SavePaymentAndOutbox {

        @Test
        @DisplayName("successful execution constructs pending payment, saves to repository, and publishes outbox event")
        void savePaymentAndOutbox_success_persistsPaymentAndPublishesOutboxEvent() {
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentTransactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment).isNotNull();
            assertThat(savedPayment.getOrderId()).isEqualTo(orderId);
            assertThat(savedPayment.getPaymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
            assertThat(savedPayment.getUserId()).isEqualTo(userId);
            assertThat(savedPayment.getTotalAmountCents()).isEqualTo(totalAmountCents);
            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);

            then(outboxWriter).should().publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void savePaymentAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");

            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentTransactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publication failure propagates exception after payment is saved")
        void savePaymentAndOutbox_outboxWriterFails_propagatesException() {
            RuntimeException outboxException = new RuntimeException("Outbox publication error");

            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse);

            assertThatThrownBy(() -> paymentTransactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse);
        }
    }

    @Nested
    @DisplayName("saveFailurePaymentAndOutbox()")
    class SaveFailurePaymentAndOutbox {

        private final String reason = "PSP gateway unreachable";

        @Test
        @DisplayName("successful execution constructs failed payment, saves to repository, and publishes failure outbox event")
        void saveFailurePaymentAndOutbox_success_persistsFailedPaymentAndPublishesOutboxEvent() {
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentTransactionalWriter.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, reason);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment).isNotNull();
            assertThat(savedPayment.getOrderId()).isEqualTo(orderId);
            assertThat(savedPayment.getPaymentIntentId()).isNull();
            assertThat(savedPayment.getUserId()).isEqualTo(userId);
            assertThat(savedPayment.getTotalAmountCents()).isEqualTo(totalAmountCents);
            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(savedPayment.getFailureReason()).isEqualTo(reason);

            then(outboxWriter).should().publishPaymentInitializationFailedEvent(orderId, triggerEventId, reason);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void saveFailurePaymentAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");

            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentTransactionalWriter.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, reason))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publication failure propagates exception after payment is saved")
        void saveFailurePaymentAndOutbox_outboxWriterFails_propagatesException() {
            RuntimeException outboxException = new RuntimeException("Outbox publication error");

            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .publishPaymentInitializationFailedEvent(orderId, triggerEventId, reason);

            assertThatThrownBy(() -> paymentTransactionalWriter.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, reason))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().publishPaymentInitializationFailedEvent(orderId, triggerEventId, reason);
        }
    }
}