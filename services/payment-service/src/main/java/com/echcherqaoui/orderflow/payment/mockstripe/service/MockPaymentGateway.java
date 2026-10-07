package com.echcherqaoui.orderflow.payment.mockstripe.service;

import com.echcherqaoui.orderflow.payment.dto.RefundCreateParams;
import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayPermanentException;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntent;
import com.echcherqaoui.orderflow.payment.mockstripe.store.MockPaymentIntentStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RefreshScope
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    private final MockPaymentIntentStore intentStore;

    private final MockPspProperties mockPspProperties;

    @Override
    public CreatePaymentIntentResponse createIntent(@NonNull String idempotencyKey, long totalAmountCents) {
        if (mockPspProperties.isSimulateOutage()) {
            log.warn("Mock PSP simulated outage triggered for idempotencyKey: {}", idempotencyKey);
            throw new PaymentGatewayTransientException();
        }

        MockPaymentIntent intent = intentStore.getOrCreate(idempotencyKey, totalAmountCents);

        return new CreatePaymentIntentResponse(intent.paymentIntentId(), intent.clientSecret());
    }

    @Override
    public void cancelIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank())
            return;

        intentStore.remove(paymentIntentId);
    }

    @Override
    public void refundPayment(@NonNull RefundCreateParams params) {
        if (mockPspProperties.isSimulateOutage()) {
            log.warn(
                  "Mock PSP simulated outage on refund for paymentIntentId: {}, triggerEventId: {}",
                  params.paymentIntentId(),
                  params.triggerEventId()
            );
            throw new PaymentGatewayTransientException("Simulated PSP outage");
        }

        try {
            MockPaymentIntent refund = intentStore.refund(params.paymentIntentId());

            if (refund == null)
                throw new PaymentGatewayPermanentException("No such payment_intent: " + params.paymentIntentId());

            log.info(
                  "Mock PSP successfully processed refund for paymentIntentId: {}, triggerEventId: {}",
                  params.paymentIntentId(),
                  params.triggerEventId()
            );

        } catch (IllegalStateException ex) {
            throw new PaymentGatewayPermanentException(ex.getMessage(), ex);
        }
    }
}