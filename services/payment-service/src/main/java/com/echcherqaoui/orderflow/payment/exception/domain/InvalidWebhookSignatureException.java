package com.echcherqaoui.orderflow.payment.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

public class InvalidWebhookSignatureException extends BaseCustomException {
    public InvalidWebhookSignatureException(IErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }

    public InvalidWebhookSignatureException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}