package com.echcherqaoui.orderflow.exception.handler;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;

@Slf4j
public class GrpcServerExceptionHandler implements GrpcExceptionHandler {

    @Override
    public StatusException handleException(@NonNull Throwable ex) {
        return switch (ex) {
            case IllegalArgumentException illegalArgEx ->
                  Status.INVALID_ARGUMENT.withDescription(illegalArgEx.getMessage()).asException();

            case BaseCustomException customEx ->
                  mapHttpStatusToGrpcStatus(customEx).asException();

            case StatusRuntimeException statusRuntimeEx ->
                  statusRuntimeEx.getStatus().asException();

            case StatusException statusEx ->
                  statusEx;

            default -> {
                log.error("Unhandled system exception occurred during gRPC execution", ex);
                yield Status.INTERNAL.withDescription("Internal server error").asException();
            }
        };
    }

    @NonNull
    private Status mapHttpStatusToGrpcStatus(@NonNull BaseCustomException ex) {
        return switch (ex.getErrorCode().getHttpStatus()) {
            case 400 -> Status.INVALID_ARGUMENT.withDescription(ex.getMessage());
            case 401 -> Status.UNAUTHENTICATED.withDescription(ex.getMessage());
            case 403 -> Status.PERMISSION_DENIED.withDescription(ex.getMessage());
            case 404 -> Status.NOT_FOUND.withDescription(ex.getMessage());
            case 409, 422 -> Status.ALREADY_EXISTS.withDescription(ex.getMessage());
            case 429 -> Status.RESOURCE_EXHAUSTED.withDescription(ex.getMessage());
            default -> Status.INTERNAL.withDescription(ex.getMessage());
        };
    }
}