package com.echcherqaoui.orderflow.payment.controller;

import com.echcherqaoui.orderflow.payment.service.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService stripeWebhookService;

    @PostMapping
    public ResponseEntity<Void> handleStripeWebhook(@RequestHeader("Stripe-Signature") String signature,
                                                    @RequestBody String payload) {
        stripeWebhookService.processWebhook(signature, payload);
        return ResponseEntity.ok().build();
    }
}