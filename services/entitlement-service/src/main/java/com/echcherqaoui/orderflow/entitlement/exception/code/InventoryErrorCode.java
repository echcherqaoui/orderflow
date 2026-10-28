package com.echcherqaoui.orderflow.entitlement.exception.code;

import com.echcherqaoui.orderflow.exception.core.IErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InventoryErrorCode implements IErrorCode {

    DUPLICATE_KEYS_BATCH("DB_409", "Batch ingestion failed. The following keys already exist: %s", 409),
    KEY_POOL_EXHAUSTED("ENT_409", "No available keys for item: %s", 409),

    DUPLICATE_ORDER_ITEMS("ENT_422", "Order contains duplicate items: %s", 422),
    EMPTY_ORDER("ENT_422_EMPTY", "Order contains no items", 422);

    private final String code;
    private final String message;
    private final int httpStatus;
}