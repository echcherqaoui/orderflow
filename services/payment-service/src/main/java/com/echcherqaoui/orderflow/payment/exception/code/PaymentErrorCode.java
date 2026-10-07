package com.echcherqaoui.orderflow.payment.exception.code;

import com.echcherqaoui.orderflow.exception.core.IErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements IErrorCode {
    INVALID_WEBHOOK_SIGNATURE("PAYMENT_WEBHOOK_401", "Invalid Stripe webhook signature", 401),
    PAYMENT_INTENT_NOT_FOUND("INTENT_404", "Payment intent not found: %s", 404),
    PAYMENT_NOT_FOUND_FOR_ORDER("PAYMENT_ORDER_404", "Payment record not found for the given order ID %s", 404);

    private final String code;
    private final String message;
    private final int httpStatus;
}
