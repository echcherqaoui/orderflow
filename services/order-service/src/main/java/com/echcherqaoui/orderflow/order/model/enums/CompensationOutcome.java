package com.echcherqaoui.orderflow.order.model.enums;

/**
 * Result of one leg of the entitlement-failure parallel compensation.
 * Used by Order.refundStatus and Order.inventoryReleaseStatus.
 * <ul>
 *   <li>null: the order is not in parallel compensation</li>
 *   <li>PENDING: command sent, result not yet received</li>
 *   <li>SUCCESS / FAILED: result received</li>
 * </ul>
 */
public enum CompensationOutcome {
    PENDING,
    SUCCESS,
    FAILED
}
