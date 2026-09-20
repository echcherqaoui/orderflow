package com.echcherqaoui.orderflow.payment.gateway;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;

import static com.echcherqaoui.orderflow.payment.exception.code.OrderErrorCode.PSP_CONNECTION_FAILED;


public class PaymentGatewayTransientException extends BaseCustomException {

    public PaymentGatewayTransientException(Throwable cause) {
        super(PSP_CONNECTION_FAILED, cause);
    }

    public PaymentGatewayTransientException() {
        super(PSP_CONNECTION_FAILED);
    }
}