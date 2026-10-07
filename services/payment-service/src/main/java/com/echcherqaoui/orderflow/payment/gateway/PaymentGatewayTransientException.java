package com.echcherqaoui.orderflow.payment.gateway;

public class PaymentGatewayTransientException extends PaymentGatewayException {

    private static final String DEFAULT_MESSAGE = "Payment gateway temporarily unavailable";

    public PaymentGatewayTransientException() {
        super(DEFAULT_MESSAGE);
    }

    public PaymentGatewayTransientException(String message) {
        super(message);
    }

    public PaymentGatewayTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}