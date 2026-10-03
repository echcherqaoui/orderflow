package com.echcherqaoui.orderflow.payment.config;

import com.echcherqaoui.orderflow.payment.dto.StripeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class StripeConfig {
}