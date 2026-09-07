package com.echcherqaoui.orderflow.inventory.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

public class ItemNotFoundException extends BaseCustomException {
    public ItemNotFoundException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
