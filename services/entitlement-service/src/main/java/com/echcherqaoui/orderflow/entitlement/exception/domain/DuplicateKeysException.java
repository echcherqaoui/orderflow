package com.echcherqaoui.orderflow.entitlement.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;

import java.util.List;

import static com.echcherqaoui.orderflow.entitlement.exception.code.InventoryErrorCode.DUPLICATE_KEYS_BATCH;


public class DuplicateKeysException extends BaseCustomException {
    public DuplicateKeysException(List<String> duplicateKeys) {
        super(DUPLICATE_KEYS_BATCH, duplicateKeys);
    }
}
