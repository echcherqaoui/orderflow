package com.echcherqaoui.orderflow.payment.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@RequiredArgsConstructor
public enum StripeEventType {

    PAYMENT_INTENT_SUCCEEDED("payment_intent.succeeded"),
    PAYMENT_INTENT_PAYMENT_FAILED("payment_intent.payment_failed");

    private final String value;

    // Cache values in a hash map once at startup for O(1) lookup
    private static final Map<String, StripeEventType> BY_VALUE = Stream.of(values())
          .collect(Collectors.toUnmodifiableMap(StripeEventType::getValue, Function.identity()));

    public static Optional<StripeEventType> from(String value) {
        return Optional.ofNullable(BY_VALUE.get(value));
    }
}