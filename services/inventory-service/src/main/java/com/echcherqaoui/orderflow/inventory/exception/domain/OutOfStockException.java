package com.echcherqaoui.orderflow.inventory.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

import java.util.UUID;

import static com.echcherqaoui.orderflow.inventory.exception.enums.InventoryErrorCode.ITEM_OUT_OF_STOCK;

public class OutOfStockException extends BaseCustomException {
    public OutOfStockException(UUID itemId) {
        super(ITEM_OUT_OF_STOCK, itemId);
    }

    public OutOfStockException(IErrorCode errorCode) {
        super(errorCode);
    }
}
