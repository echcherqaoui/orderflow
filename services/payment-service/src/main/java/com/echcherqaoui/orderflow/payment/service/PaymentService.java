package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.PaymentCancelProjection;
import com.echcherqaoui.orderflow.payment.dto.PrepareRefundResult;
import com.echcherqaoui.orderflow.payment.dto.RefundCreateParams;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayPermanentException;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentGateway paymentGateway;
    private final PaymentPersistenceService persistenceService;

    private void executeWriterCall(@NonNull Runnable writerCall, UUID orderId) {
        try {
            writerCall.run();
        } catch (DataIntegrityViolationException ex) {
            if (persistenceService.existsByOrderId(orderId)) {
                log.info("Duplicate order ID {} intercepted at DB level. Ignoring.", orderId);
                return;
            }
            log.error("Data integrity violation non-related to duplicate order ID for order {}", orderId, ex);
            throw ex;
        }
    }

    public void initializePayment(@lombok.NonNull UUID orderId,
                                  @lombok.NonNull String userId,
                                  long totalAmountCents,
                                  String triggerEventId) {
        // Idempotency check
        if (persistenceService.existsByOrderId(orderId)) {
            log.info("Payment session already initialized for orderId: {}", orderId);
            return;
        }

        CreatePaymentIntentResponse pspResponse;

        try {
            //External call (Mock execution) OUTSIDE database transaction
            pspResponse = paymentGateway.createIntent(orderId.toString(), totalAmountCents);
        } catch (Exception ex) {
            String reasonCode;
            if (ex instanceof PaymentGatewayTransientException e) {
                log.warn("PSP declined payment intent creation for orderId: {}", orderId, e);
                reasonCode = "PSP_PAYMENT_DECLINED";
            } else {
                log.error("Unhandled error creating PSP payment intent for orderId: {}", orderId, ex);
                reasonCode = "PSP_GATEWAY_UNAVAILABLE";
            }

            executeWriterCall(() -> persistenceService.saveFailurePaymentAndOutbox(
                  orderId,
                  userId,
                  totalAmountCents,
                  triggerEventId,
                  reasonCode
            ), orderId);
            return;
        }

        executeWriterCall(() -> persistenceService.savePaymentAndOutbox(
              orderId,
              userId,
              totalAmountCents,
              pspResponse,
              triggerEventId
        ), orderId);
    }

    public void cancelPayment(@lombok.NonNull UUID orderId,
                              String paymentIntentId,
                              String reason,
                              String triggerEventId) {
        Optional<PaymentCancelProjection> existingPayment = persistenceService.findByOrderId(orderId);

        if (existingPayment.isEmpty()) {
            log.info("Payment record not found for orderId: {}.", orderId);
            return;
        }

        // Idempotency Guard: Silently ignore if already cancelled
        if (existingPayment.get().status() == PaymentStatus.CANCELLED) {
            log.info("Payment for orderId {} is already CANCELLED. Ignoring duplicate command.", orderId);
            return;
        }

        String targetIntentId = paymentIntentId != null ? paymentIntentId : existingPayment.get().paymentIntentId();

        // External PSP Call OUTSIDE DB transaction
        try {
            log.info("Invoking PSP gateway to cancel payment intent: {}", targetIntentId);
            paymentGateway.cancelIntent(targetIntentId);
        } catch (Exception ex) {
            log.error("Failed to cancel payment intent {} on PSP gateway. Proceeding with local saga compensation.", targetIntentId, ex);
        }

        // Persist DB state + Outbox Event in single transaction
        persistenceService.saveCancellationAndOutbox(
              orderId,
              targetIntentId,
              reason != null ? reason : "Compensation triggered",
              triggerEventId
        );
    }

    public void processRefund(UUID orderId,
                              String paymentIntentId,
                              String reason,
                              String triggerEventId) {

        // Atomic Claim / Status Verification
        PrepareRefundResult prepareResult = persistenceService.prepareRefund(orderId);

        switch (prepareResult) {
            case TERMINAL -> {
                log.info("Refund already in terminal state for orderId={}. Aborting.", orderId);
                return;
            }
            case RETRY_IN_FLIGHT ->
                log.info(
                      "Detected in-flight/retried refund for orderId={}. Proceeding to PSP with triggerEventId={}",
                      orderId,
                      triggerEventId
                );
            case INITIAL_CLAIM ->
                log.info("Acquired initial refund lock for orderId={}.", orderId);
        }

        RefundCreateParams params = new RefundCreateParams(paymentIntentId, reason, triggerEventId);

        // External PSP Network Call
        try {
            paymentGateway.refundPayment(params);
        } catch (PaymentGatewayTransientException ex) {
            log.warn("Transient PSP failure calling refund for orderId={}: {}", orderId, ex.getMessage());
            throw ex; // Triggers Kafka retry; prepareRefund will allow attempt #2
        } catch (PaymentGatewayPermanentException ex) {
            log.error("Permanent PSP refund execution failed for orderId={}, paymentIntentId={}", orderId, paymentIntentId, ex);
            persistenceService.failRefund(orderId, paymentIntentId, ex.getMessage(), triggerEventId);
            return;
        }

        // PSP Succeeded -> Commit DB success state & Outbox event
        try {
            persistenceService.completeRefund(orderId, paymentIntentId, triggerEventId);
        } catch (Exception ex) {
            log.error("CRITICAL: PSP refund succeeded on gateway, but local completeRefund failed for orderId={}", orderId, ex);
            throw ex; // Re-throw to prevent calling failRefund() when PSP actually succeeded
        }
    }
}