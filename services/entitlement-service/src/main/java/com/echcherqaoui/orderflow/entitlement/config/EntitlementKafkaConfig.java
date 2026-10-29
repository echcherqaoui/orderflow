package com.echcherqaoui.orderflow.entitlement.config;

import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateOrderItemsException;
import com.echcherqaoui.orderflow.entitlement.exception.domain.EmptyOrderException;
import com.echcherqaoui.orderflow.kafka.config.NotRetryableExceptionsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class EntitlementKafkaConfig {
    @Bean
    NotRetryableExceptionsProvider inventoryNotRetryable() {
        return () -> List.of(EmptyOrderException.class, DuplicateOrderItemsException.class);
    }
}