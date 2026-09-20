package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.dto.PaymentCancelProjection;
import com.echcherqaoui.orderflow.payment.exception.domain.PaymentNotFoundException;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentPersistenceServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private PaymentPersistenceService paymentPersistenceService;

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
    @DisplayName("existsByOrderId()")
    class ExistsByOrderId {

        @Test
        @DisplayName("returns true when payment exists for given orderId")
        void existsByOrderId_exists_returnsTrue() {
            given(paymentRepository.existsByOrderId(orderId)).willReturn(true);

            boolean result = paymentPersistenceService.existsByOrderId(orderId);

            assertThat(result).isTrue();
            then(paymentRepository).should().existsByOrderId(orderId);
        }

        @Test
        @DisplayName("returns false when payment does not exist for given orderId")
        void existsByOrderId_doesNotExist_returnsFalse() {
            given(paymentRepository.existsByOrderId(orderId)).willReturn(false);

            boolean result = paymentPersistenceService.existsByOrderId(orderId);

            assertThat(result).isFalse();
            then(paymentRepository).should().existsByOrderId(orderId);
        }
    }

    @Nested
    @DisplayName("findByOrderId()")
    class FindByOrderId {

        @Test
        @DisplayName("returns projection when payment exists for given orderId")
        void findByOrderId_exists_returnsProjection() {
            PaymentCancelProjection projection = new PaymentCancelProjection(PaymentStatus.PENDING, "pi_123456");
            given(paymentRepository.findByOrderId(orderId, PaymentCancelProjection.class))
                  .willReturn(Optional.of(projection));

            Optional<PaymentCancelProjection> result = paymentPersistenceService.findByOrderId(orderId);

            assertThat(result).contains(projection);
            then(paymentRepository).should().findByOrderId(orderId, PaymentCancelProjection.class);
        }

        @Test
        @DisplayName("returns empty optional when payment does not exist for given orderId")
        void findByOrderId_doesNotExist_returnsEmpty() {
            given(paymentRepository.findByOrderId(orderId, PaymentCancelProjection.class))
                  .willReturn(Optional.empty());

            Optional<PaymentCancelProjection> result = paymentPersistenceService.findByOrderId(orderId);

            assertThat(result).isEmpty();
            then(paymentRepository).should().findByOrderId(orderId, PaymentCancelProjection.class);
        }
    }

    @Nested
    @DisplayName("findByPaymentIntentId()")
    class FindByPaymentIntentId {

        private final String paymentIntentId = "pi_123456";

        @Test
        @DisplayName("returns payment entity when payment exists for given paymentIntentId")
        void findByPaymentIntentId_exists_returnsPayment() {
            Payment payment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setStatus(PaymentStatus.PENDING);

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(payment));

            Payment result = paymentPersistenceService.findByPaymentIntentId(paymentIntentId);

            assertThat(result).isNotNull();
            assertThat(result.getPaymentIntentId()).isEqualTo(paymentIntentId);
            assertThat(result.getOrderId()).isEqualTo(orderId);
            then(paymentRepository).should().findByPaymentIntentId(paymentIntentId);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment intent ID does not exist")
        void findByPaymentIntentId_doesNotExist_throwsPaymentNotFoundException() {
            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.findByPaymentIntentId(paymentIntentId))
                  .isInstanceOf(PaymentNotFoundException.class);

            then(paymentRepository).should().findByPaymentIntentId(paymentIntentId);
        }
    }

    @Nested
    @DisplayName("savePaymentAndOutbox()")
    class SavePaymentAndOutbox {

        @Test
        @DisplayName("successful execution constructs pending payment, saves to repository, and publishes outbox event")
        void savePaymentAndOutbox_success_persistsPaymentAndPublishesOutboxEvent() {
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment).isNotNull();
            assertThat(savedPayment.getOrderId()).isEqualTo(orderId);
            assertThat(savedPayment.getPaymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
            assertThat(savedPayment.getUserId()).isEqualTo(userId);
            assertThat(savedPayment.getTotalAmountCents()).isEqualTo(totalAmountCents);
            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);

            then(outboxWriter).should().publishPaymentInitiatedEvent(orderId, pspResponse, triggerEventId);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void savePaymentAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");

            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId))
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
                  .publishPaymentInitiatedEvent(orderId, pspResponse, triggerEventId);

            assertThatThrownBy(() -> paymentPersistenceService.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().publishPaymentInitiatedEvent(orderId, pspResponse, triggerEventId);
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

            paymentPersistenceService.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, reason);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment).isNotNull();
            assertThat(savedPayment.getOrderId()).isEqualTo(orderId);
            assertThat(savedPayment.getPaymentIntentId()).isNull();
            assertThat(savedPayment.getUserId()).isEqualTo(userId);
            assertThat(savedPayment.getTotalAmountCents()).isEqualTo(totalAmountCents);
            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(savedPayment.getFailureReason()).isEqualTo(reason);

            then(outboxWriter).should().publishPaymentInitializationFailedEvent(orderId, reason, triggerEventId);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void saveFailurePaymentAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            RuntimeException dbException = new RuntimeException("Database constraint violation");

            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, reason))
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
                  .publishPaymentInitializationFailedEvent(orderId, reason, triggerEventId);

            assertThatThrownBy(() -> paymentPersistenceService.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, reason))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().publishPaymentInitializationFailedEvent(orderId, reason, triggerEventId);
        }
    }

    @Nested
    @DisplayName("saveCancellationAndOutbox()")
    class SaveCancellationAndOutbox {

        private final String paymentIntentId = "pi_123456";
        private final String cancellationReason = "Order cancelled by customer";

        @Test
        @DisplayName("successful execution updates payment status to CANCELLED, sets reason, saves payment, and publishes outbox event")
        void saveCancellationAndOutbox_success_updatesPaymentAndPublishesOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setUserId(userId)
                  .setPaymentIntentId(paymentIntentId)
                  .setTotalAmountCents(totalAmountCents)
                  .setStatus(PaymentStatus.PENDING);

            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.saveCancellationAndOutbox(orderId, paymentIntentId, cancellationReason, triggerEventId);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(savedPayment.getFailureReason()).isEqualTo(cancellationReason);
            assertThat(savedPayment.getPaymentIntentId()).isEqualTo(paymentIntentId);

            then(outboxWriter).should().publishPaymentCancelledEvent(orderId, paymentIntentId, cancellationReason, triggerEventId);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when target payment record is not found")
        void saveCancellationAndOutbox_paymentNotFound_throwsPaymentNotFoundException() {
            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.saveCancellationAndOutbox(orderId, paymentIntentId, cancellationReason, triggerEventId))
                  .isInstanceOf(PaymentNotFoundException.class);

            then(paymentRepository).should().findByOrderId(orderId, Payment.class);
            then(paymentRepository).shouldHaveNoMoreInteractions();
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void saveCancellationAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException dbException = new RuntimeException("Database error");

            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.saveCancellationAndOutbox(orderId, paymentIntentId, cancellationReason, triggerEventId))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publication failure propagates exception after payment state is updated")
        void saveCancellationAndOutbox_outboxWriterFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException outboxException = new RuntimeException("Outbox publication failed");

            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .publishPaymentCancelledEvent(orderId, paymentIntentId, cancellationReason, triggerEventId);

            assertThatThrownBy(() -> paymentPersistenceService.saveCancellationAndOutbox(orderId, paymentIntentId, cancellationReason, triggerEventId))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().publishPaymentCancelledEvent(orderId, paymentIntentId, cancellationReason, triggerEventId);
        }
    }

    @Nested
    @DisplayName("markPaymentChargedAndOutbox()")
    class MarkPaymentChargedAndOutbox {

        private final String paymentIntentId = "pi_charged_123";

        @Test
        @DisplayName("successful execution updates payment status to SUCCESS, saves payment, and writes charged outbox event")
        void markPaymentChargedAndOutbox_success_updatesStatusAndWritesOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setStatus(PaymentStatus.PENDING);

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.markPaymentChargedAndOutbox(paymentIntentId);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            then(outboxWriter).should().writePaymentChargedEvent(orderId.toString(), paymentIntentId);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment intent ID is not found")
        void markPaymentChargedAndOutbox_paymentNotFound_throwsPaymentNotFoundException() {
            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.markPaymentChargedAndOutbox(paymentIntentId))
                  .isInstanceOf(PaymentNotFoundException.class);

            then(paymentRepository).should().findByPaymentIntentId(paymentIntentId);
            then(paymentRepository).shouldHaveNoMoreInteractions();
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void markPaymentChargedAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException dbException = new RuntimeException("Database error");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.markPaymentChargedAndOutbox(paymentIntentId))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publication failure propagates exception after payment status is updated")
        void markPaymentChargedAndOutbox_outboxWriterFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException outboxException = new RuntimeException("Outbox publication failed");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .writePaymentChargedEvent(orderId.toString(), paymentIntentId);

            assertThatThrownBy(() -> paymentPersistenceService.markPaymentChargedAndOutbox(paymentIntentId))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().writePaymentChargedEvent(orderId.toString(), paymentIntentId);
        }
    }

    @Nested
    @DisplayName("markPaymentFailedAndOutbox()")
    class MarkPaymentFailedAndOutbox {

        private final String paymentIntentId = "pi_failed_123";
        private final String failureReason = "Card declined";

        @Test
        @DisplayName("successful execution updates payment status to FAILED, sets reason, saves payment, and writes failed outbox event")
        void markPaymentFailedAndOutbox_success_updatesStatusAndWritesOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setStatus(PaymentStatus.PENDING);

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.markPaymentFailedAndOutbox(paymentIntentId, failureReason);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(savedPayment.getFailureReason()).isEqualTo(failureReason);

            then(outboxWriter).should().writePaymentFailedEvent(orderId.toString(), paymentIntentId, failureReason);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment intent ID is not found")
        void markPaymentFailedAndOutbox_paymentNotFound_throwsPaymentNotFoundException() {
            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.markPaymentFailedAndOutbox(paymentIntentId, failureReason))
                  .isInstanceOf(PaymentNotFoundException.class);

            then(paymentRepository).should().findByPaymentIntentId(paymentIntentId);
            then(paymentRepository).shouldHaveNoMoreInteractions();
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void markPaymentFailedAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException dbException = new RuntimeException("Database error");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.markPaymentFailedAndOutbox(paymentIntentId, failureReason))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publication failure propagates exception after payment status is updated")
        void markPaymentFailedAndOutbox_outboxWriterFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException outboxException = new RuntimeException("Outbox publication failed");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .writePaymentFailedEvent(orderId.toString(), paymentIntentId, failureReason);

            assertThatThrownBy(() -> paymentPersistenceService.markPaymentFailedAndOutbox(paymentIntentId, failureReason))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().writePaymentFailedEvent(orderId.toString(), paymentIntentId, failureReason);
        }
    }
}