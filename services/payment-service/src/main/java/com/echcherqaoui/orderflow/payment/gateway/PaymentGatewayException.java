package com.echcherqaoui.orderflow.payment.gateway;

public abstract class PaymentGatewayException extends RuntimeException {

    protected PaymentGatewayException(String message) {
        super(message);
    }

    protected PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}