package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentRequest;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.ConfirmPaymentIntentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock-stripe/payment_intents")
@RequiredArgsConstructor
public class MockStripeController {

    private final MockStripeService mockStripeService;

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ConfirmPaymentIntentResponse> confirmPaymentIntent(@PathVariable("id") String paymentIntentId,
                                                                             @Valid @RequestBody ConfirmPaymentIntentRequest request) {
        ConfirmPaymentIntentResponse response = mockStripeService.confirmAndTriggerWebhook(paymentIntentId, request);
        return ResponseEntity.ok(response);
    }
}