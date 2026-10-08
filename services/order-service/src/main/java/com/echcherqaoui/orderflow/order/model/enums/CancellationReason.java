package com.echcherqaoui.orderflow.order.model.enums;

public enum CancellationReason {
    NONE,
    PAYMENT_FAILED,
    RESERVATION_EXPIRED,
    ENTITLEMENT_FAILED,
    REFUND_FAILED,
    CRITICAL_DUAL_FAILURE
}
