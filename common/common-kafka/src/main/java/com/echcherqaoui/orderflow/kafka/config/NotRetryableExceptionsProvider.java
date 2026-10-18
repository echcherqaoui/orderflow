package com.echcherqaoui.orderflow.kafka.config;

import java.util.List;

@FunctionalInterface
public interface NotRetryableExceptionsProvider {
    List<Class<? extends Exception>> notRetryable();
}