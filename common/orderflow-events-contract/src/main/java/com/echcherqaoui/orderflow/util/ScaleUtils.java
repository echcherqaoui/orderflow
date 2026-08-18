package com.echcherqaoui.orderflow.util;


import java.math.BigDecimal;
import java.util.Objects;

import static java.math.RoundingMode.HALF_UP;

public final class ScaleUtils {

    private ScaleUtils() {
    }

    /**
     * Converts a BigDecimal amount to the smallest currency unit (cents, fils, centimes…).
     * Example: 1500.75 → 150075
     */
    public static Long toScaledLong(BigDecimal amount, int decimalPlaces) {
        Objects.requireNonNull(amount, "amount must not be null");

        return amount
              .setScale(decimalPlaces, HALF_UP)
              .movePointRight(decimalPlaces)
              .longValueExact();
    }

    /**
     * Reconstructs a BigDecimal from the smallest currency unit.
     * Example: 150075, 2 → 1500.75
     * 1500, 0 → 1500
     */
    public static BigDecimal fromScaledLong(long cents, int decimalPlaces) {
        return BigDecimal.valueOf(cents, decimalPlaces);
    }
}