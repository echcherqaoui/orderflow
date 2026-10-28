package com.echcherqaoui.orderflow.entitlement.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;

import java.util.UUID;

import static com.echcherqaoui.orderflow.entitlement.exception.code.InventoryErrorCode.KEY_POOL_EXHAUSTED;

public class KeyPoolExhaustedException extends BaseCustomException {
    public KeyPoolExhaustedException(UUID itemId) {
        super(KEY_POOL_EXHAUSTED, itemId);
    }
}