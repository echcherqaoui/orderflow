package com.echcherqaoui.orderflow.payment.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;


public class PaymentNotFoundException extends BaseCustomException {
    public PaymentNotFoundException(IErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }

    public PaymentNotFoundException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}