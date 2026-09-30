package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentRequest;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.PaymentError;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockStripeService {

    private final MockPaymentIntentStore intentStore;
    private final MockStripePaymentProcessor paymentProcessor;
    private final MockStripeWebhookDispatcher webhookDispatcher;
    private final MockPspProperties mockPspProperties;

    public ConfirmPaymentIntentResponse confirmAndTriggerWebhook(String paymentIntentId,
                                                                 @lombok.NonNull ConfirmPaymentIntentRequest request) {
        boolean isSuccess = paymentProcessor.decideOutcome(request.paymentMethod());
        PaymentError error = isSuccess ? null : paymentProcessor.resolvePaymentError(request.paymentMethod());
        String errorCode = error != null ? error.code() : null;

        TransitionResult result = intentStore.recordAttempt(
              paymentIntentId,
              request.clientSecret(),
              isSuccess,
              errorCode,
              mockPspProperties.getMaxConfirmAttempts()
        );

        MockPaymentIntent intent = result.intent();

        if (intent == null)
            throw new IllegalArgumentException("No such payment_intent: " + paymentIntentId);

        // If no state transition occurred (intent is terminal/already processed), return early without sending a duplicate webhook
        if (!result.applied())
            return new ConfirmPaymentIntentResponse(
                  paymentIntentId,
                  intent.status().getValue(),
                  intent.clientSecret()
            );

        MockPaymentIntentStatus intentStatus = intent.status();

        webhookDispatcher.dispatchWebhookAsync(
              paymentIntentId,
              intent.totalAmountCents(),
              intentStatus,
              error,
              result.intent().attemptCount()
        );

        return new ConfirmPaymentIntentResponse(
              paymentIntentId,
              intentStatus.name(),
              intent.clientSecret()
        );
    }
}