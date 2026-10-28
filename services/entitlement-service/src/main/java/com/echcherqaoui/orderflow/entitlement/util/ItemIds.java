package com.echcherqaoui.orderflow.entitlement.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class ItemIds {
    private static final UUID NAMESPACE = UUID.fromString("6f1d2c3a-0000-4000-8000-000000000001");

    @lombok.NonNull
    public static UUID fromName(@lombok.NonNull String name) {
        String normalized = name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return UUID.nameUUIDFromBytes((NAMESPACE + ":" + normalized).getBytes(StandardCharsets.UTF_8));
    }
}