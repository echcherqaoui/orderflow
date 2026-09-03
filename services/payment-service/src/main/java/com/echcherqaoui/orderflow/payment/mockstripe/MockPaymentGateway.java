package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.exception.domain.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RefreshScope
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    private final MockPaymentIntentStore intentStore;

    @Value("${orderflow.mock-psp.simulate-outage:false}")
    private boolean simulatePspOutage;

    @Override
    public CreatePaymentIntentResponse createIntent(@NonNull String idempotencyKey, long totalAmountCents) {
        if (simulatePspOutage) {
            log.warn("Mock PSP simulated outage triggered for idempotencyKey: {}", idempotencyKey);
            throw new PaymentGatewayTransientException();
        }

        return intentStore.findByIdempotencyKey(idempotencyKey)
              .map(existing -> new CreatePaymentIntentResponse(
                    existing.paymentIntentId(),
                    existing.clientSecret()
              )).orElseGet(() -> {
                  MockPaymentIntent newIntent = MockPaymentIntent.create(totalAmountCents);

                  intentStore.save(idempotencyKey, newIntent);

                  return new CreatePaymentIntentResponse(
                        newIntent.paymentIntentId(),
                        newIntent.clientSecret()
                  );
              });
    }

    @Override
    public void cancelIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank())
            return;

        intentStore.remove(paymentIntentId);
    }
}