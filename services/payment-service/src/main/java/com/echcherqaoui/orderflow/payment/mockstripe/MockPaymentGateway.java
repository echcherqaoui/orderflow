package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
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

        MockPaymentIntent intent = intentStore.computeIfAbsent(idempotencyKey, totalAmountCents);

        return new CreatePaymentIntentResponse(intent.paymentIntentId(), intent.clientSecret());
    }

    @Override
    public void cancelIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank())
            return;

        intentStore.remove(paymentIntentId);
    }
}