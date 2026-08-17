package com.echcherqaoui.orderflow.exception.core;

import lombok.Getter;
import org.jspecify.annotations.NonNull;

@Getter
public abstract class BaseCustomException extends RuntimeException {

    private final transient IErrorCode errorCode;
    private final transient Object[] args;

    protected BaseCustomException(@NonNull IErrorCode errorCode,
                                  Object... args) {
        super(errorCode.formatMessage(args));
        this.errorCode = errorCode;
        this.args = args;
    }
}