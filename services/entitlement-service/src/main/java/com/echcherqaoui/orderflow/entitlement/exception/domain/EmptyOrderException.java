package com.echcherqaoui.orderflow.entitlement.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;

import static com.echcherqaoui.orderflow.entitlement.exception.code.InventoryErrorCode.EMPTY_ORDER;

public class EmptyOrderException extends BaseCustomException {
    public EmptyOrderException() {
        super(EMPTY_ORDER);
    }
}