package com.echcherqaoui.orderflow.order.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

public class ResourceNotFoundException extends BaseCustomException {
    public ResourceNotFoundException(IErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }

    public ResourceNotFoundException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
