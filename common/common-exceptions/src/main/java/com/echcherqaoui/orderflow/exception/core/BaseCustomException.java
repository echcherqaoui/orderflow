package com.echcherqaoui.orderflow.exception.core;

import lombok.Getter;
import org.jspecify.annotations.NonNull;

@Getter
public abstract class BaseCustomException extends RuntimeException {

    private final transient IErrorCode errorCode;
    private final transient Object[] args;

    // Translating caught exceptions (preserves root cause)
    protected BaseCustomException(@NonNull IErrorCode errorCode,
                                  Throwable cause,
                                  Object... args) {
        super(errorCode.formatMessage(args), cause);
        this.errorCode = errorCode;
        this.args = args;
    }

    // Throwing directly from business logic (no cause)
    protected BaseCustomException(@NonNull IErrorCode errorCode,
                                  Object... args) {
        super(errorCode.formatMessage(args));
        this.errorCode = errorCode;
        this.args = args;
    }
}