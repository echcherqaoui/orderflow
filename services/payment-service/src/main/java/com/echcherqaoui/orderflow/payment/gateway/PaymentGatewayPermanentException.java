package com.echcherqaoui.orderflow.payment.gateway;

public class PaymentGatewayPermanentException extends PaymentGatewayException {

    public PaymentGatewayPermanentException(String message) {
        super(message);
    }

    public PaymentGatewayPermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}