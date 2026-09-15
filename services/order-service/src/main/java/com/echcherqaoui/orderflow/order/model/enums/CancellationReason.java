package com.echcherqaoui.orderflow.order.model.enums;

/**
 * Why an order ended up CANCELLED or FAILED. Kept separate from {@link OrderStatus}
 * so status stays a small state machine used for control flow, while this field
 * tracks the precise root cause that triggered saga compensation and forced
 * an order into a terminal CANCELLED state.
 */
public enum CancellationReason {
    NONE,
    PAYMENT_FAILED,
    RESERVATION_EXPIRED,
    ENTITLEMENT_FAILED,
    REFUND_FAILED,
    CRITICAL_DUAL_FAILURE
}
