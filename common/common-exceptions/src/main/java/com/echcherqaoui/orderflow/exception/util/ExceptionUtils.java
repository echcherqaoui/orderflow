package com.echcherqaoui.orderflow.exception.util;

import org.jspecify.annotations.NonNull;
import org.springframework.web.context.request.WebRequest;

public final class ExceptionUtils {
    
    private ExceptionUtils() {}

    @NonNull
    public static String sanitizePath(@NonNull WebRequest request) {
        return request.getDescription(false)
              .replace("uri=", "");
    }
}