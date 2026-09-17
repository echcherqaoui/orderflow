package com.echcherqaoui.orderflow.payment.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StripeEventTypeTest {

    @ParameterizedTest
    @CsvSource({
          "payment_intent.succeeded, PAYMENT_INTENT_SUCCEEDED",
          "payment_intent.payment_failed, PAYMENT_INTENT_PAYMENT_FAILED"
    })
    @DisplayName("from() returns correct enum for valid event types")
    void from_validValue_returnsEnum(String inputValue, StripeEventType expected) {
        assertThat(StripeEventType.from(inputValue)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid.event", "PAYMENT_INTENT_SUCCEEDED", ""})
    @DisplayName("from() returns empty for unknown values")
    void from_unknownValue_returnsEmpty(String inputValue) {
        assertThat(StripeEventType.from(inputValue)).isEmpty();
    }
}