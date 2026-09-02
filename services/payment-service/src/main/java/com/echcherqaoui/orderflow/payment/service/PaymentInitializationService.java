package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        //External call (Mock execution) OUTSIDE database transaction
        CreatePaymentIntentResponse pspResponse = paymentGateway.createIntent(orderId.toString(), totalAmountCents);

        // Persist state & outbox atomically INSIDE database transaction
        transactionalWriter.savePaymentAndOutbox(
              orderId,
              userId,
              totalAmountCents,
              pspResponse,
              triggerEventId
        );
    }
}