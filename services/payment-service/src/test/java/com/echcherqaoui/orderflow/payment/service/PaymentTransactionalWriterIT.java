package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.payment.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

@SpringBootTest
class PaymentTransactionalWriterIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentTransactionalWriter transactionalWriter;

    @Autowired
    private PaymentRepository paymentRepository;

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
}