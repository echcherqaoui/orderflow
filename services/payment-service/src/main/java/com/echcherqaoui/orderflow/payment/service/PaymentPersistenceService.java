package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.PaymentCancelProjection;
import com.echcherqaoui.orderflow.payment.exception.domain.PaymentNotFoundException;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentAttemptStatus;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static com.echcherqaoui.orderflow.payment.exception.code.OrderErrorCode.PAYMENT_INTENT_NOT_FOUND;
import static com.echcherqaoui.orderflow.payment.exception.code.OrderErrorCode.PAYMENT_NOT_FOUND_FOR_ORDER;
import static com.echcherqaoui.orderflow.payment.model.PaymentStatus.CANCELLED;
import static com.echcherqaoui.orderflow.payment.model.PaymentStatus.FAILED;
import static com.echcherqaoui.orderflow.payment.model.PaymentStatus.PENDING;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentPersistenceService {

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;

    @Transactional(readOnly = true)
    public boolean existsByOrderId(UUID orderId) {
        return paymentRepository.existsByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentCancelProjection> findByOrderId(UUID orderId) {
        return paymentRepository.findByOrderId(orderId, PaymentCancelProjection.class);
    }

    public Payment findByPaymentIntentId(String paymentIntentId) {
        return paymentRepository.findByPaymentIntentId(paymentIntentId)
              .orElseThrow(() -> new PaymentNotFoundException(PAYMENT_INTENT_NOT_FOUND));
    }

    @Transactional
    public void savePaymentAndOutbox(UUID orderId,
                                     String userId,
                                     long totalAmountCents,
                                     @NonNull CreatePaymentIntentResponse pspResponse,
                                     String triggerEventId) {
        Payment payment = new Payment()
              .setOrderId(orderId)
              .setPaymentIntentId(pspResponse.paymentIntentId())
              .setUserId(userId)
              .setTotalAmountCents(totalAmountCents)
              .setStatus(PENDING);

        paymentRepository.saveAndFlush(payment); // flush now so a duplicate orderId throws immediately, not later

        outboxWriter.publishPaymentInitiatedEvent(orderId, pspResponse, triggerEventId);
    }

    @Transactional
    public void saveFailurePaymentAndOutbox(UUID orderId,
                                            String userId,
                                            long totalAmountCents,
                                            String triggerEventId,
                                            String reason) {
        Payment payment = new Payment()
              .setOrderId(orderId)
              .setUserId(userId)
              .setTotalAmountCents(totalAmountCents)
              .setStatus(FAILED)
              .setFailureReason(reason);

        paymentRepository.saveAndFlush(payment); // flush now so a duplicate orderId throws immediately, not later

        outboxWriter.publishPaymentInitializationFailedEvent(orderId, reason, triggerEventId);
    }

    @Transactional
    public void saveCancellationAndOutbox(UUID orderId,
                                          String paymentIntentId,
                                          String reason,
                                          String triggerEventId) {
        Payment payment = paymentRepository.findByOrderId(orderId, Payment.class)
              .orElseThrow(() -> new PaymentNotFoundException(PAYMENT_NOT_FOUND_FOR_ORDER, orderId));

        payment.setStatus(CANCELLED)
              .setFailureReason(reason);

        paymentRepository.saveAndFlush(payment);

        // Persist outbox event atomically with state update
        outboxWriter.publishPaymentCancelledEvent(
              orderId,
              paymentIntentId,
              reason,
              triggerEventId
        );

        log.info("Payment cancellation persisted and outbox event published for orderId: {}", orderId);
    }

    @Transactional
    public void recordSuccessAndOutbox(@NonNull String paymentIntentId) {
        Payment payment = findByPaymentIntentId(paymentIntentId);

        // Record SUCCESS attempt
        payment.addAttempt(PaymentAttemptStatus.SUCCESS, null);

        // Transition payment status to terminal SUCCESS
        payment.setStatus(PaymentStatus.SUCCESS)
              .setFailureReason(null);

        paymentRepository.saveAndFlush(payment);

        // Write outbox event
        outboxWriter.writePaymentChargedEvent(
              payment.getOrderId().toString(),
              paymentIntentId
        );

        log.info("Payment [{}] marked as SUCCESS and outbox event published", paymentIntentId);
    }

    @Transactional
    public void recordFailedAttempt(@NonNull String paymentIntentId, String errorCode, String errorMessage) {
        Payment payment = findByPaymentIntentId(paymentIntentId);

        payment.addAttempt(PaymentAttemptStatus.FAILED, errorCode);

        // Update failure reason for trace, but leave main status intact (PENDING/REQUIRES_PAYMENT_METHOD)
        payment.setFailureReason(errorMessage);

        paymentRepository.saveAndFlush(payment);

        // NO outbox event emitted for transient failures (allows user to retry)
        log.info(
              "Recorded transient payment attempt failure for [{}] with code: {}",
              paymentIntentId,
              errorCode
        );
    }

    @Transactional
    public void recordCanceledAndOutbox(@NonNull String paymentIntentId, String errorCode, String errorMessage) {
        Payment payment = findByPaymentIntentId(paymentIntentId);

        // Record CANCELED attempt
        payment.addAttempt(PaymentAttemptStatus.CANCELED, errorCode);

        // Transition payment status to terminal FAILED
        payment.setStatus(PaymentStatus.FAILED)
              .setFailureReason(errorMessage);

        paymentRepository.saveAndFlush(payment);

        // Write terminal failure outbox event (triggers order cancellation & inventory release downstream)
        outboxWriter.writePaymentFailedEvent(
              payment.getOrderId().toString(),
              paymentIntentId,
              errorMessage
        );

        log.info(
              "Payment [{}] marked as FAILED (CANCELED) and outbox failure event published. Reason: {}",
              paymentIntentId,
              errorMessage
        );
    }
}
