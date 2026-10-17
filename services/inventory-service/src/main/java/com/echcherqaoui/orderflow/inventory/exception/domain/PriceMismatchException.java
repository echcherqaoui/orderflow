package com.echcherqaoui.orderflow.inventory.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

import static com.echcherqaoui.orderflow.inventory.exception.code.InventoryErrorCode.PRICE_MISMATCH;

public class PriceMismatchException extends BaseCustomException {
    public PriceMismatchException(Object... args) {
        super(PRICE_MISMATCH, args);
    }

    public PriceMismatchException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
