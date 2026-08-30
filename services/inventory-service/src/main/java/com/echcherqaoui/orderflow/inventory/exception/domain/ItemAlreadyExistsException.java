package com.echcherqaoui.orderflow.inventory.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.ITEM_ALREADY_EXISTS;

public class ItemAlreadyExistsException extends BaseCustomException {
    public ItemAlreadyExistsException(String name) {
        super(ITEM_ALREADY_EXISTS, name);
    }

    public ItemAlreadyExistsException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
