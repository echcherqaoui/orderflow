package com.echcherqaoui.orderflow.inventory.config;

import com.echcherqaoui.orderflow.inventory.exception.domain.PriceMismatchException;
import com.echcherqaoui.orderflow.kafka.config.NotRetryableExceptionsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class InventoryKafkaConfig {
    @Bean
    NotRetryableExceptionsProvider inventoryNotRetryable() {
        return () -> List.of(PriceMismatchException.class, IllegalArgumentException.class);
    }
}