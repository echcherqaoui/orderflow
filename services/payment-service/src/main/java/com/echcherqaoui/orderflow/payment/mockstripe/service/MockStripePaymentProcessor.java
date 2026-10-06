package com.echcherqaoui.orderflow.payment.mockstripe.service;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.PaymentError;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class MockStripePaymentProcessor {

    private final MockPspProperties mockPspProperties;

    public boolean decideOutcome(String paymentMethod) {
        if ("pm_card_chargeDeclined".equals(paymentMethod)
              || "pm_card_insufficientFunds".equals(paymentMethod)
              || "pm_card_radarBlock".equals(paymentMethod)
              || "pm_card_fraudulent".equals(paymentMethod))
            return false;

        return ThreadLocalRandom.current().nextDouble() >= mockPspProperties.getFailureRate();
    }

    public PaymentError resolvePaymentError(String paymentMethod) {
        if ("pm_card_radarBlock".equals(paymentMethod) || "pm_card_fraudulent".equals(paymentMethod))
            return new PaymentError("fraudulent", "Transaction blocked due to high fraud risk.");

        if ("pm_card_insufficientFunds".equals(paymentMethod))
            return new PaymentError("insufficient_funds", "Your card has insufficient funds.");

        return new PaymentError("card_declined", "Your card was declined.");
    }
}