package com.echcherqaoui.orderflow.util;

import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.util.Objects;

public final class InstantConverter {

    private InstantConverter() {}

    public static Instant toInstant(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        return Instant.ofEpochSecond(
              timestamp.getSeconds(),
              timestamp.getNanos()
        );
    }

    public static Timestamp toTimestamp(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");

        return Timestamp.newBuilder()
              .setSeconds(instant.getEpochSecond())
              .setNanos(instant.getNano())
              .build();
    }
}