package com.echcherqaoui.orderflow.inventory.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

public class OutOfStockException extends BaseCustomException {
    public OutOfStockException(IErrorCode errorCode) {
        super(errorCode);
    }
}
