package com.echcherqaoui.orderflow.order.model.enums;

public enum SagaStep {
    // HAPPY PATH (Forward Progression)
    ORDER_CREATED,                      // Order entity initialized
    INVENTORY_RESERVED,                 // Initial sync gRPC reservation complete
    EXTENDING_INVENTORY,                // Stock TTL extension to checkout window in progress
    INITIALIZING_PAYMENT,               // Outbox charge command sent, awaiting payment session
    PAYMENT_SESSION_ACTIVE,             // Payment intent created & SSE active
    CONFIRMING_INVENTORY,               // Payment charged, confirming stock
    GRANTING_ENTITLEMENT,               // Inventory confirmed, issuing license/entitlement
    COMPLETED,                          // Terminal Success

    // ACTIVE ROLLBACKS & COMPENSATION IN-PROGRESS
    REVERSING_PAYMENT,                  // Reservation extension failed; voiding/cancelling payment session
    REVERSING_INVENTORY,                // Payment failed; releasing inventory reservation
    REVERSING_PAYMENT_OUT_OF_STOCK,     // Inventory expired; issuing refund
    CANCEL_IN_PROGRESS,                 // Entitlement failed; triggering parallel compensations
    CANCEL_AWAITING_PARALLEL_RESPONSE,  // Waiting for parallel refund & inventory release results

    // TERMINAL REVERSALS & MANUAL INTERVENTIONS
    REVERSED,                           // Fully compensated (clean cancellation)
    REFUNDED,                           // Refunded successfully following out-of-stock failure
    MANUAL_INTERVENTION_REQUIRED,       // Automatic refund failed after inventory expiration; requires manual financial reconciliation
    MANUAL_FINANCE_INTERVENTION,        // Parallel compensation: refund failed, stock released
    MANUAL_INVENTORY_INTERVENTION,      // Parallel compensation: refund succeeded, stock release failed
    MANUAL_FULL_INTERVENTION            // Parallel compensation: both refund and stock release failed
}