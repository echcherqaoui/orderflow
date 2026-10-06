package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.payment.dto.PaymentCancelProjection;
import com.echcherqaoui.orderflow.payment.dto.PrepareRefundResult;
import com.echcherqaoui.orderflow.payment.exception.domain.PaymentNotFoundException;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentAttemptStatus;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

@SpringBootTest
@ActiveProfiles("test")
class PaymentPersistenceServiceIT implements WithPostgres {

    @Autowired
    private PaymentPersistenceService transactionalWriter;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    @MockitoSpyBean
    private OutboxEventRepository outboxEventRepository;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-789";
    private final long totalAmountCents = 15000L;
    private final String triggerEventId = UUID.randomUUID().toString();
    private final CreatePaymentIntentResponse pspResponse =
          new CreatePaymentIntentResponse("pi_test_999", "secret_test_999");

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    private Payment paymentWithAttempts() {
        return txTemplate.execute(status -> {
            Payment payment = paymentRepository.findAll().getFirst();
            Hibernate.initialize(payment.getAttempts());
            return payment;
        });
    }

    @Nested
    @DisplayName("existsByOrderId()")
    class ExistsByOrderId {

        @Test
        @DisplayName("returns true when payment entity exists for given order ID")
        void existsByOrderId_whenExists_returnsTrue() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            assertThat(transactionalWriter.existsByOrderId(orderId)).isTrue();
        }

        @Test
        @DisplayName("returns false when payment entity does not exist for given order ID")
        void existsByOrderId_whenDoesNotExist_returnsFalse() {
            assertThat(transactionalWriter.existsByOrderId(orderId)).isFalse();
        }
    }

    @Nested
    @DisplayName("findByOrderId()")
    class FindByOrderId {

        @Test
        @DisplayName("returns projection when payment entity exists for given order ID")
        void findByOrderId_whenExists_returnsProjection() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            Optional<PaymentCancelProjection> projection = transactionalWriter.findByOrderId(orderId);

            assertThat(projection).isPresent();
            assertThat(projection.get().status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(projection.get().paymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
        }

        @Test
        @DisplayName("returns empty optional when payment entity does not exist")
        void findByOrderId_whenDoesNotExist_returnsEmptyOptional() {
            Optional<PaymentCancelProjection> projection = transactionalWriter.findByOrderId(orderId);

            assertThat(projection).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByPaymentIntentId()")
    class FindByPaymentIntentId {

        @Test
        @DisplayName("returns payment entity when found by payment intent ID")
        void findByPaymentIntentId_whenExists_returnsPayment() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            Payment payment = transactionalWriter.findByPaymentIntentId(pspResponse.paymentIntentId());

            assertThat(payment).isNotNull();
            assertThat(payment.getOrderId()).isEqualTo(orderId);
            assertThat(payment.getPaymentIntentId()).isEqualTo(pspResponse.paymentIntentId());
        }

        @Test
        @DisplayName("throws PaymentNotFoundException when payment intent ID does not exist")
        void findByPaymentIntentId_whenDoesNotExist_throwsPaymentNotFoundException() {
            assertThatThrownBy(() -> transactionalWriter.findByPaymentIntentId("pi_non_existent"))
                  .isInstanceOf(PaymentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("savePaymentAndOutbox()")
    class SavePaymentAndOutbox {

        @Test
        @DisplayName("atomic commit persists payment entity and outbox event within the same database transaction")
        void savePaymentAndOutbox_success_persistsPaymentAndOutboxAtomically() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

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
        @DisplayName("outbox persistence exception causes total transactional rollback for payment insertion")
        void savePaymentAndOutbox_outboxFailure_rollsBackEntireTransaction() {
            willThrow(new RuntimeException("Outbox database persistence failure"))
                  .given(outboxEventRepository)
                  .save(any(OutboxEvent.class));

            assertThatThrownBy(() ->
                  transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId)
            ).isInstanceOf(RuntimeException.class)
                  .hasMessage("Outbox database persistence failure");

            assertThat(paymentRepository.existsByOrderId(orderId)).isFalse();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("null pspResponse throws NullPointerException and prevents persistence")
        void savePaymentAndOutbox_nullPspResponse_throwsExceptionAndAborts() {
            assertThatThrownBy(() ->
                  transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, null, triggerEventId)
            ).isInstanceOf(NullPointerException.class);

            assertThat(paymentRepository.existsByOrderId(orderId)).isFalse();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }
    }

    @Nested
    @DisplayName("saveFailurePaymentAndOutbox()")
    class SaveFailurePaymentAndOutbox {

        private final String failureReason = "PSP gateway connection timeout";

        @Test
        @DisplayName("atomic commit persists failed payment entity and failure outbox event within the same database transaction")
        void saveFailurePaymentAndOutbox_success_persistsFailedPaymentAndOutboxAtomically() {
            transactionalWriter.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, failureReason);

            assertThat(paymentRepository.existsByOrderId(orderId)).isTrue();

            List<Payment> payments = paymentRepository.findAll();
            assertThat(payments).hasSize(1);

            Payment payment = payments.getFirst();
            assertThat(payment.getOrderId()).isEqualTo(orderId);
            assertThat(payment.getPaymentIntentId()).isNull();
            assertThat(payment.getUserId()).isEqualTo(userId);
            assertThat(payment.getTotalAmountCents()).isEqualTo(totalAmountCents);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo(failureReason);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentInitializationFailedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("outbox persistence exception causes total transactional rollback for failed payment insertion")
        void saveFailurePaymentAndOutbox_outboxFailure_rollsBackEntireTransaction() {
            willThrow(new RuntimeException("Outbox database persistence failure"))
                  .given(outboxEventRepository)
                  .save(any(OutboxEvent.class));

            assertThatThrownBy(() ->
                  transactionalWriter.saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, failureReason)
            ).isInstanceOf(RuntimeException.class)
                  .hasMessage("Outbox database persistence failure");

            assertThat(paymentRepository.existsByOrderId(orderId)).isFalse();
            assertThat(paymentRepository.findAll()).isEmpty();
            assertThat(outboxEventRepository.findAll()).isEmpty();
        }
    }

    @Nested
    @DisplayName("saveCancellationAndOutbox()")
    class SaveCancellationAndOutbox {

        private final String paymentIntentId = "pi_cancel_123";
        private final String cancellationReason = "Customer requested order cancellation";

        @Test
        @DisplayName("atomic commit updates payment status to CANCELLED and persists outbox event")
        void saveCancellationAndOutbox_success_persistsCancellationAndOutboxAtomically() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            outboxEventRepository.deleteAll();

            transactionalWriter.saveCancellationAndOutbox(orderId, paymentIntentId, cancellationReason, triggerEventId);

            List<Payment> payments = paymentRepository.findAll();
            assertThat(payments).hasSize(1);

            Payment payment = payments.getFirst();
            assertThat(payment.getOrderId()).isEqualTo(orderId);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(payment.getFailureReason()).isEqualTo(cancellationReason);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentCancelledEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("cancelling non-existent payment throws PaymentNotFoundException and aborts outbox publication")
        void saveCancellationAndOutbox_nonExistentPayment_throwsPaymentNotFoundException() {
            assertThatThrownBy(() ->
                  transactionalWriter.saveCancellationAndOutbox(orderId, paymentIntentId, cancellationReason, triggerEventId)
            ).isInstanceOf(PaymentNotFoundException.class);

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("outbox persistence failure rolls back payment cancellation status change")
        void saveCancellationAndOutbox_outboxFailure_rollsBackEntireTransaction() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            willThrow(new RuntimeException("Outbox database persistence failure"))
                  .given(outboxEventRepository)
                  .save(any(OutboxEvent.class));

            assertThatThrownBy(() ->
                  transactionalWriter.saveCancellationAndOutbox(orderId, paymentIntentId, cancellationReason, triggerEventId)
            ).isInstanceOf(RuntimeException.class)
                  .hasMessage("Outbox database persistence failure");

            Payment payment = paymentRepository.findAll().getFirst();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("recordSuccessAndOutbox()")
    class RecordSuccessAndOutbox {

        @Test
        @DisplayName("atomic commit creates SUCCESS attempt, updates status to SUCCESS, and writes outbox event")
        void recordSuccessAndOutbox_success_updatesStatusAppendsAttemptAndWritesOutboxAtomically() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            outboxEventRepository.deleteAll();

            transactionalWriter.recordSuccessAndOutbox(pspResponse.paymentIntentId());

            Payment payment = paymentWithAttempts();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getFailureReason()).isNull();

            assertThat(payment.getAttempts()).hasSize(1);
            assertThat(payment.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);
            assertThat(outboxEvents.getFirst().getEventType()).isEqualTo("PaymentChargedEvent");
        }

        @Test
        @DisplayName("non-existent payment intent throws PaymentNotFoundException and aborts outbox publication")
        void recordSuccessAndOutbox_nonExistentPaymentIntent_throwsPaymentNotFoundException() {
            assertThatThrownBy(() -> transactionalWriter.recordSuccessAndOutbox("pi_non_existent"))
                  .isInstanceOf(PaymentNotFoundException.class);

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("outbox persistence failure rolls back payment status update and attempt persistence")
        void recordSuccessAndOutbox_outboxFailure_rollsBackEntireTransaction() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            willThrow(new RuntimeException("Outbox database persistence failure"))
                  .given(outboxEventRepository)
                  .save(any(OutboxEvent.class));

            String paymentIntentId = pspResponse.paymentIntentId();

            assertThatThrownBy(() -> transactionalWriter.recordSuccessAndOutbox(paymentIntentId))
                  .isInstanceOf(RuntimeException.class)
                  .hasMessage("Outbox database persistence failure");

            Payment payment = paymentWithAttempts();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getAttempts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("recordFailedAttempt()")
    class RecordFailedAttempt {

        private final String errorCode = "card_declined";
        private final String errorMessage = "Your card was declined.";

        @Test
        @DisplayName("appends FAILED attempt and sets failureReason without changing main payment status or emitting outbox event")
        void recordFailedAttempt_success_appendsAttemptWithoutOutbox() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            outboxEventRepository.deleteAll();

            transactionalWriter.recordFailedAttempt(pspResponse.paymentIntentId(), errorCode, errorMessage);

            Payment payment = paymentWithAttempts();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getFailureReason()).isEqualTo(errorMessage);

            assertThat(payment.getAttempts()).hasSize(1);
            assertThat(payment.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(payment.getAttempts().getFirst().getErrorCode()).isEqualTo(errorCode);

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("non-existent payment intent throws PaymentNotFoundException")
        void recordFailedAttempt_nonExistentPaymentIntent_throwsPaymentNotFoundException() {
            assertThatThrownBy(() -> transactionalWriter.recordFailedAttempt("pi_non_existent", errorCode, errorMessage))
                  .isInstanceOf(PaymentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("recordCanceledAndOutbox()")
    class RecordCanceledAndOutbox {

        private final String errorCode = "payment_intent_canceled";
        private final String errorMessage = "The payment intent was canceled.";

        @Test
        @DisplayName("atomic commit creates CANCELED attempt, updates status to FAILED, and writes terminal failure outbox event")
        void recordCanceledAndOutbox_success_updatesStatusAppendsAttemptAndWritesOutboxAtomically() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            outboxEventRepository.deleteAll();

            transactionalWriter.recordCanceledAndOutbox(pspResponse.paymentIntentId(), errorCode, errorMessage);

            Payment payment = paymentWithAttempts();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo(errorMessage);

            assertThat(payment.getAttempts()).hasSize(1);
            assertThat(payment.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.CANCELED);
            assertThat(payment.getAttempts().getFirst().getErrorCode()).isEqualTo(errorCode);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);
            assertThat(outboxEvents.getFirst().getEventType()).isEqualTo("PaymentFailedEvent");
        }

        @Test
        @DisplayName("non-existent payment intent throws PaymentNotFoundException and aborts outbox publication")
        void recordCanceledAndOutbox_nonExistentPaymentIntent_throwsPaymentNotFoundException() {
            assertThatThrownBy(() -> transactionalWriter.recordCanceledAndOutbox("pi_non_existent", errorCode, errorMessage))
                  .isInstanceOf(PaymentNotFoundException.class);

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("outbox persistence failure rolls back payment status change and attempt persistence")
        void recordCanceledAndOutbox_outboxFailure_rollsBackEntireTransaction() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            willThrow(new RuntimeException("Outbox database persistence failure"))
                  .given(outboxEventRepository)
                  .save(any(OutboxEvent.class));

            String paymentIntentId = pspResponse.paymentIntentId();

            assertThatThrownBy(() -> transactionalWriter.recordCanceledAndOutbox(paymentIntentId, errorCode, errorMessage))
                  .isInstanceOf(RuntimeException.class)
                  .hasMessage("Outbox database persistence failure");

            Payment payment = paymentWithAttempts();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getAttempts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("prepareRefund()")
    class PrepareRefund {

        @Test
        @DisplayName("returns INITIAL_CLAIM and transitions status to REFUND_PENDING when payment status is SUCCESS")
        void prepareRefund_whenStatusIsSuccess_claimsAndReturnsInitialClaim() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            transactionalWriter.recordSuccessAndOutbox(pspResponse.paymentIntentId());

            PrepareRefundResult result = transactionalWriter.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.INITIAL_CLAIM);

            Payment payment = paymentRepository.findAll().getFirst();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        }

        @Test
        @DisplayName("returns RETRY_IN_FLIGHT when payment status is already REFUND_PENDING")
        void prepareRefund_whenStatusIsRefundPending_returnsRetryInFlight() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            transactionalWriter.recordSuccessAndOutbox(pspResponse.paymentIntentId());
            transactionalWriter.prepareRefund(orderId);

            PrepareRefundResult result = transactionalWriter.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.RETRY_IN_FLIGHT);
        }

        @Test
        @DisplayName("returns TERMINAL when payment status is not eligible for refund")
        void prepareRefund_whenStatusIsNotSuccessOrPending_returnsTerminal() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            PrepareRefundResult result = transactionalWriter.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.TERMINAL);
        }

        @Test
        @DisplayName("returns TERMINAL when payment entity does not exist")
        void prepareRefund_whenPaymentDoesNotExist_returnsTerminal() {
            PrepareRefundResult result = transactionalWriter.prepareRefund(orderId);

            assertThat(result).isEqualTo(PrepareRefundResult.TERMINAL);
        }
    }

    @Nested
    @DisplayName("completeRefund()")
    class CompleteRefund {

        @Test
        @DisplayName("atomic commit updates payment status to REFUNDED and persists outbox event")
        void completeRefund_success_persistsRefundedStatusAndOutboxAtomically() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            transactionalWriter.recordSuccessAndOutbox(pspResponse.paymentIntentId());
            transactionalWriter.prepareRefund(orderId);
            outboxEventRepository.deleteAll();

            transactionalWriter.completeRefund(orderId, pspResponse.paymentIntentId(), triggerEventId);

            Payment payment = paymentRepository.findAll().getFirst();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("PaymentRefundedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("completing refund for non-existent payment throws PaymentNotFoundException")
        void completeRefund_nonExistentPayment_throwsPaymentNotFoundException() {
            String paymentIntentId = pspResponse.paymentIntentId();

            assertThatThrownBy(() ->
                  transactionalWriter.completeRefund(orderId, paymentIntentId, triggerEventId)
            ).isInstanceOf(PaymentNotFoundException.class);

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("outbox persistence failure rolls back payment status update to REFUND_PENDING")
        void completeRefund_outboxFailure_rollsBackEntireTransaction() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            transactionalWriter.recordSuccessAndOutbox(pspResponse.paymentIntentId());
            transactionalWriter.prepareRefund(orderId);

            willThrow(new RuntimeException("Outbox database persistence failure"))
                  .given(outboxEventRepository)
                  .save(any(OutboxEvent.class));

            String paymentIntentId = pspResponse.paymentIntentId();

            assertThatThrownBy(() ->
                  transactionalWriter.completeRefund(orderId, paymentIntentId, triggerEventId)
            ).isInstanceOf(RuntimeException.class)
                  .hasMessage("Outbox database persistence failure");

            Payment payment = paymentRepository.findAll().getFirst();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        }
    }

    @Nested
    @DisplayName("failRefund()")
    class FailRefund {

        private final String failureReason = "PSP refund declined by issuing bank";

        @Test
        @DisplayName("atomic commit updates payment status to REFUND_FAILED and persists refund failure outbox event")
        void failRefund_success_persistsFailedRefundStatusAndOutboxAtomically() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            transactionalWriter.recordSuccessAndOutbox(pspResponse.paymentIntentId());
            transactionalWriter.prepareRefund(orderId);
            outboxEventRepository.deleteAll();

            transactionalWriter.failRefund(orderId, pspResponse.paymentIntentId(), failureReason, triggerEventId);

            Payment payment = paymentRepository.findAll().getFirst();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);

            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);

            OutboxEvent outboxEvent = outboxEvents.getFirst();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(outboxEvent.getAggregateType()).isEqualTo("payment.events");
            assertThat(outboxEvent.getEventType()).isEqualTo("RefundFailedEvent");
            assertThat(outboxEvent.getPayload()).isNotEmpty();
        }

        @Test
        @DisplayName("failing refund for non-existent payment throws PaymentNotFoundException")
        void failRefund_nonExistentPayment_throwsPaymentNotFoundException() {
            String paymentIntentId = pspResponse.paymentIntentId();

            assertThatThrownBy(() ->
                  transactionalWriter.failRefund(orderId, paymentIntentId, failureReason, triggerEventId)
            ).isInstanceOf(PaymentNotFoundException.class);

            assertThat(outboxEventRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("outbox persistence failure rolls back payment status update to REFUND_PENDING")
        void failRefund_outboxFailure_rollsBackEntireTransaction() {
            transactionalWriter.savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
            transactionalWriter.recordSuccessAndOutbox(pspResponse.paymentIntentId());
            transactionalWriter.prepareRefund(orderId);

            willThrow(new RuntimeException("Outbox database persistence failure"))
                  .given(outboxEventRepository)
                  .save(any(OutboxEvent.class));

            String paymentIntentId = pspResponse.paymentIntentId();

            assertThatThrownBy(() ->
                  transactionalWriter.failRefund(orderId, paymentIntentId, failureReason, triggerEventId)
            ).isInstanceOf(RuntimeException.class)
                  .hasMessage("Outbox database persistence failure");

            Payment payment = paymentRepository.findAll().getFirst();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        }
    }
}