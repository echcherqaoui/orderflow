package com.echcherqaoui.orderflow.payment.dto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orderflow.mock-psp")
public record StripeProperties(String webhookSecret) {}