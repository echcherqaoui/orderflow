package com.echcherqaoui.orderflow.exception.util;

import io.grpc.StatusRuntimeException;

import java.util.function.Predicate;

/**
 * Evaluates whether a gRPC error is transient and safe for retry or circuit breaker tracking.
 * Returns true only for recoverable status codes (UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED).
 */
public class GrpcFailurePredicate implements Predicate<Throwable> {
    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException ex) {
            return switch (ex.getStatus().getCode()) {
                case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED -> true;
                default -> false;
            };
        }
        return false;
    }
}