package com.echcherqaoui.orderflow.payment.dto;

public enum PrepareRefundResult {
    INITIAL_CLAIM,    // SUCCESS -> REFUND_PENDING (Won the atomic DB lock)
    RETRY_IN_FLIGHT,  // Already REFUND_PENDING (Sequential Kafka retry / duplicate)
    TERMINAL          // REFUNDED, REFUND_FAILED, or missing
}