package com.echcherqaoui.orderflow.order.model.enums;

/**
 * Used for Order.refundStatus and Order.inventoryReleaseStatus. Null on
 * Order until the corresponding event (PaymentRefundedEvent/RefundFailedEvent
 * or InventoryReleasedEvent) arrives.
 */
public enum CompensationOutcome {
    PENDING,
    SUCCESS,
    FAILED
}
