package com.echcherqaoui.orderflow.entitlement.exception.code;

import com.echcherqaoui.orderflow.exception.core.IErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InventoryErrorCode implements IErrorCode {

    DUPLICATE_KEYS_BATCH("DB_409", "Batch ingestion failed. The following keys already exist: %s", 409);

    private final String code;
    private final String message;
    private final int httpStatus;
}