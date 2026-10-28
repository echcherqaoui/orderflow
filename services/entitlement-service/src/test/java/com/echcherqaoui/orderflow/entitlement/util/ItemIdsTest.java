package com.echcherqaoui.orderflow.entitlement.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemIdsTest {

    @Test
    @DisplayName("fromName generates deterministic UUID for identical inputs")
    void fromName_deterministic() {
        UUID id1 = ItemIds.fromName("Windows Pro");
        UUID id2 = ItemIds.fromName("Windows Pro");

        assertThat(id1).isNotNull();
        assertThat(id1).isEqualTo(id2);
    }

    @ParameterizedTest(name = "Normalizes input \"{0}\" to match canonical UUID")
    @CsvSource({
          "Windows Pro, windows pro",
          "  Windows   Pro  , windows pro",
          "WINDOWS PRO, windows pro",
          "windows pro, windows pro"
    })
    void fromName_normalizesInputCorrectly(String input, String canonicalVariant) {
        UUID expected = ItemIds.fromName(canonicalVariant);
        UUID actual = ItemIds.fromName(input);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("null input throws NullPointerException due to @NonNull constraint")
    void fromName_nullInput_throwsException() {
        assertThatThrownBy(() -> ItemIds.fromName(null))
              .isInstanceOf(NullPointerException.class);
    }
}