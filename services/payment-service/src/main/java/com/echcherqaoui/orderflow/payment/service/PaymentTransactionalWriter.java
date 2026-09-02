package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.echcherqaoui.orderflow.payment.model.PaymentStatus.PENDING;

@Component
@RequiredArgsConstructor
public class PaymentTransactionalWriter {

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;

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

        paymentRepository.save(payment);

        outboxWriter.publishPaymentInitiatedEvent(orderId, triggerEventId, pspResponse);
    }
}