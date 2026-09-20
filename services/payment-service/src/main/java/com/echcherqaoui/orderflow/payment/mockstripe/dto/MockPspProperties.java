package com.echcherqaoui.orderflow.payment.mockstripe.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Getter
@Setter
@RefreshScope
@Component
@ConfigurationProperties(prefix = "orderflow.mock-psp")
public class MockPspProperties {
    private boolean simulateOutage;
    private double failureRate;
    private String webhookUrl;
}