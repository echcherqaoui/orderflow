package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInitializationService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentTransactionalWriter transactionalWriter;

    private boolean executeWriterCall(@NonNull Runnable writerCall, UUID orderId) {
        try {
            writerCall.run();
            return true;
        } catch (DataIntegrityViolationException ex) {
            if (paymentRepository.existsByOrderId(orderId)) {
                log.info("Duplicate order ID {} intercepted at DB level. Ignoring.", orderId);
                return false;
            }
            log.error("Data integrity violation non-related to duplicate order ID for order {}", orderId, ex);
            throw ex;
        }
    }

    public void initializePayment(UUID orderId,
                                  String userId,
                                  long totalAmountCents,
                                  String triggerEventId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        // Idempotency check
        if (paymentRepository.existsByOrderId(orderId)) {
            log.info("Payment session already initialized for orderId: {}", orderId);
            return;
        }

        CreatePaymentIntentResponse pspResponse;

        try {
            //External call (Mock execution) OUTSIDE database transaction
            pspResponse = paymentGateway.createIntent(orderId.toString(), totalAmountCents);
        } catch (Exception ex) {
            log.error("Failed to create PSP payment intent for orderId: {}", orderId, ex);

            // Persist FAILED status & outbox event atomically
            executeWriterCall(() -> transactionalWriter.saveFailurePaymentAndOutbox(
                  orderId,
                  userId,
                  totalAmountCents,
                  triggerEventId,
                  ex.getMessage()
            ), orderId);
            return;
        }

        executeWriterCall(() -> transactionalWriter.savePaymentAndOutbox(
              orderId,
              userId,
              totalAmountCents,
              pspResponse,
              triggerEventId
        ), orderId);
    }
}