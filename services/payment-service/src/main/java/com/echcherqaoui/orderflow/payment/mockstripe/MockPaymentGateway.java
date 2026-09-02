package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    private final MockPaymentIntentStore intentStore;

    @Override
    public CreatePaymentIntentResponse createIntent(@NonNull String idempotencyKey, long totalAmountCents) {

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