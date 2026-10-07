package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.PaymentCancelProjection;
import com.echcherqaoui.orderflow.payment.dto.PrepareRefundResult;
import com.echcherqaoui.orderflow.payment.exception.domain.PaymentNotFoundException;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentAttemptStatus;
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
    @DisplayName("recordSuccessAndOutbox()")
    class RecordSuccessAndOutbox {

        private final String paymentIntentId = "pi_charged_123";

        @Test
        @DisplayName("successful execution transitions payment status to SUCCESS, records SUCCESS attempt, saves payment, and writes charged outbox event")
        void recordSuccessAndOutbox_success_updatesStatusAppendsAttemptAndWritesOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setStatus(PaymentStatus.PENDING);

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.recordSuccessAndOutbox(paymentIntentId);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(savedPayment.getAttempts()).hasSize(1);
            assertThat(savedPayment.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);

            then(outboxWriter).should().writePaymentChargedEvent(orderId.toString(), paymentIntentId);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment intent ID is not found")
        void recordSuccessAndOutbox_paymentNotFound_throwsPaymentNotFoundException() {
            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.recordSuccessAndOutbox(paymentIntentId))
                  .isInstanceOf(PaymentNotFoundException.class);

            then(paymentRepository).should().findByPaymentIntentId(paymentIntentId);
            then(paymentRepository).shouldHaveNoMoreInteractions();
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void recordSuccessAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException dbException = new RuntimeException("Database error");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.recordSuccessAndOutbox(paymentIntentId))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publication failure propagates exception after payment status is updated")
        void recordSuccessAndOutbox_outboxWriterFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException outboxException = new RuntimeException("Outbox publication failed");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .writePaymentChargedEvent(orderId.toString(), paymentIntentId);

            assertThatThrownBy(() -> paymentPersistenceService.recordSuccessAndOutbox(paymentIntentId))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().writePaymentChargedEvent(orderId.toString(), paymentIntentId);
        }
    }

    @Nested
    @DisplayName("recordFailedAttempt()")
    class RecordFailedAttempt {

        private final String paymentIntentId = "pi_attempt_123";
        private final String errorCode = "card_declined";
        private final String errorMessage = "Your card was declined.";

        @Test
        @DisplayName("records FAILED attempt and updates failureReason without changing main payment status or publishing outbox event")
        void recordFailedAttempt_success_appendsAttemptAndSavesPaymentWithoutOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setStatus(PaymentStatus.PENDING);

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.recordFailedAttempt(paymentIntentId, errorCode, errorMessage);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(savedPayment.getFailureReason()).isEqualTo(errorMessage);
            assertThat(savedPayment.getAttempts()).hasSize(1);
            assertThat(savedPayment.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(savedPayment.getAttempts().getFirst().getErrorCode()).isEqualTo(errorCode);

            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment intent ID is not found")
        void recordFailedAttempt_paymentNotFound_throwsPaymentNotFoundException() {
            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.recordFailedAttempt(paymentIntentId, errorCode, errorMessage))
                  .isInstanceOf(PaymentNotFoundException.class);

            then(paymentRepository).should().findByPaymentIntentId(paymentIntentId);
            then(paymentRepository).shouldHaveNoMoreInteractions();
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository save failure propagates exception")
        void recordFailedAttempt_repositorySaveFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException dbException = new RuntimeException("Database error");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.recordFailedAttempt(paymentIntentId, errorCode, errorMessage))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }
    }

    @Nested
    @DisplayName("recordCanceledAndOutbox()")
    class RecordCanceledAndOutbox {

        private final String paymentIntentId = "pi_failed_123";
        private final String errorCode = "payment_intent_canceled";
        private final String errorMessage = "The payment intent was canceled.";

        @Test
        @DisplayName("successful execution transitions payment status to FAILED, records CANCELED attempt, sets reason, saves payment, and writes terminal failure outbox event")
        void recordCanceledAndOutbox_success_updatesStatusAppendsAttemptAndWritesOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(paymentIntentId)
                  .setStatus(PaymentStatus.PENDING);

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.recordCanceledAndOutbox(paymentIntentId, errorCode, errorMessage);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(savedPayment.getFailureReason()).isEqualTo(errorMessage);
            assertThat(savedPayment.getAttempts()).hasSize(1);
            assertThat(savedPayment.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.CANCELED);
            assertThat(savedPayment.getAttempts().getFirst().getErrorCode()).isEqualTo(errorCode);

            then(outboxWriter).should().writePaymentFailedEvent(orderId.toString(), paymentIntentId, errorMessage);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment intent ID is not found")
        void recordCanceledAndOutbox_paymentNotFound_throwsPaymentNotFoundException() {
            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.recordCanceledAndOutbox(paymentIntentId, errorCode, errorMessage))
                  .isInstanceOf(PaymentNotFoundException.class);

            then(paymentRepository).should().findByPaymentIntentId(paymentIntentId);
            then(paymentRepository).shouldHaveNoMoreInteractions();
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository save failure propagates exception and aborts outbox publication")
        void recordCanceledAndOutbox_repositorySaveFails_propagatesExceptionAndAbortsOutbox() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException dbException = new RuntimeException("Database error");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.recordCanceledAndOutbox(paymentIntentId, errorCode, errorMessage))
                  .isSameAs(dbException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("outbox publication failure propagates exception after payment status is updated")
        void recordCanceledAndOutbox_outboxWriterFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.PENDING);
            RuntimeException outboxException = new RuntimeException("Outbox publication failed");

            given(paymentRepository.findByPaymentIntentId(paymentIntentId))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));
            willThrow(outboxException)
                  .given(outboxWriter)
                  .writePaymentFailedEvent(orderId.toString(), paymentIntentId, errorMessage);

            assertThatThrownBy(() -> paymentPersistenceService.recordCanceledAndOutbox(paymentIntentId, errorCode, errorMessage))
                  .isSameAs(outboxException);

            then(paymentRepository).should().saveAndFlush(any(Payment.class));
            then(outboxWriter).should().writePaymentFailedEvent(orderId.toString(), paymentIntentId, errorMessage);
        }
    }

    @Nested
    @DisplayName("prepareRefund()")
    class PrepareRefund {

        @Test
        @DisplayName("returns INITIAL_CLAIM when repository claim succeeds")
        void prepareRefund_claimSucceeds_returnsInitialClaim() {
            given(paymentRepository.markRefundPending(orderId)).willReturn(1);

            PrepareRefundResult result = paymentPersistenceService.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.INITIAL_CLAIM);
            then(paymentRepository).should().markRefundPending(orderId);
            then(paymentRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("returns RETRY_IN_FLIGHT when claim fails but existing status is REFUND_PENDING")
        void prepareRefund_claimFailsAndStatusIsRefundPending_returnsRetryInFlight() {
            given(paymentRepository.markRefundPending(orderId)).willReturn(0);
            given(paymentRepository.findStatusByOrderId(orderId))
                  .willReturn(Optional.of(PaymentStatus.REFUND_PENDING));

            PrepareRefundResult result = paymentPersistenceService.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.RETRY_IN_FLIGHT);
            then(paymentRepository).should().markRefundPending(orderId);
            then(paymentRepository).should().findStatusByOrderId(orderId);
        }

        @Test
        @DisplayName("returns TERMINAL when claim fails and existing status is not REFUND_PENDING")
        void prepareRefund_claimFailsAndStatusIsNotRefundPending_returnsTerminal() {
            given(paymentRepository.markRefundPending(orderId)).willReturn(0);
            given(paymentRepository.findStatusByOrderId(orderId))
                  .willReturn(Optional.of(PaymentStatus.REFUNDED));

            PrepareRefundResult result = paymentPersistenceService.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.TERMINAL);
            then(paymentRepository).should().markRefundPending(orderId);
            then(paymentRepository).should().findStatusByOrderId(orderId);
        }

        @Test
        @DisplayName("returns TERMINAL when claim fails and payment does not exist")
        void prepareRefund_claimFailsAndPaymentNotFound_returnsTerminal() {
            given(paymentRepository.markRefundPending(orderId)).willReturn(0);
            given(paymentRepository.findStatusByOrderId(orderId))
                  .willReturn(Optional.empty());

            PrepareRefundResult result = paymentPersistenceService.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.TERMINAL);
            then(paymentRepository).should().markRefundPending(orderId);
            then(paymentRepository).should().findStatusByOrderId(orderId);
        }
    }

    @Nested
    @DisplayName("completeRefund()")
    class CompleteRefund {

        private final String paymentIntentId = "pi_refund_123";

        @Test
        @DisplayName("updates status to REFUNDED, saves payment, and publishes refunded outbox event")
        void completeRefund_success_updatesStatusAndPublishesOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setStatus(PaymentStatus.REFUND_PENDING);

            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.completeRefund(orderId, paymentIntentId, triggerEventId);

            then(paymentRepository).should().saveAndFlush(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

            then(outboxWriter).should().publishPaymentRefundedEvent(orderId, paymentIntentId, triggerEventId);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment is not found for orderId")
        void completeRefund_paymentNotFound_throwsException() {
            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.completeRefund(orderId, paymentIntentId, triggerEventId))
                  .isInstanceOf(PaymentNotFoundException.class);

            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository failure propagates exception and skips outbox publication")
        void completeRefund_repositoryFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.REFUND_PENDING);
            RuntimeException dbException = new RuntimeException("Database failure");

            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.saveAndFlush(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.completeRefund(orderId, paymentIntentId, triggerEventId))
                  .isSameAs(dbException);

            verifyNoInteractions(outboxWriter);
        }
    }

    @Nested
    @DisplayName("failRefund()")
    class FailRefund {

        private final String paymentIntentId = "pi_refund_123";
        private final String failureReason = "Chargeback already active";

        @Test
        @DisplayName("updates status to REFUND_FAILED, saves payment, and publishes refund failed outbox event")
        void failRefund_success_updatesStatusAndPublishesOutbox() {
            Payment existingPayment = new Payment()
                  .setOrderId(orderId)
                  .setStatus(PaymentStatus.REFUND_PENDING);

            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.save(any(Payment.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            paymentPersistenceService.failRefund(orderId, paymentIntentId, failureReason, triggerEventId);

            then(paymentRepository).should().save(paymentCaptor.capture());
            Payment savedPayment = paymentCaptor.getValue();

            assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);

            then(outboxWriter).should().publishRefundFailedEvent(orderId, paymentIntentId, failureReason, triggerEventId);
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment is not found for orderId")
        void failRefund_paymentNotFound_throwsException() {
            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentPersistenceService.failRefund(orderId, paymentIntentId, failureReason, triggerEventId))
                  .isInstanceOf(PaymentNotFoundException.class);

            verifyNoInteractions(outboxWriter);
        }

        @Test
        @DisplayName("repository failure propagates exception and skips outbox publication")
        void failRefund_repositoryFails_propagatesException() {
            Payment existingPayment = new Payment().setOrderId(orderId).setStatus(PaymentStatus.REFUND_PENDING);
            RuntimeException dbException = new RuntimeException("Database failure");

            given(paymentRepository.findByOrderId(orderId, Payment.class))
                  .willReturn(Optional.of(existingPayment));
            given(paymentRepository.save(any(Payment.class))).willThrow(dbException);

            assertThatThrownBy(() -> paymentPersistenceService.failRefund(orderId, paymentIntentId, failureReason, triggerEventId))
                  .isSameAs(dbException);

            verifyNoInteractions(outboxWriter);
        }
    }
}