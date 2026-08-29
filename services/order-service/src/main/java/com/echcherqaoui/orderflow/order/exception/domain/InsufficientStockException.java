package com.echcherqaoui.orderflow.order.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;

import static com.echcherqaoui.orderflow.order.exception.code.OrderErrorCode.INSUFFICIENT_STOCK;

public class InsufficientStockException extends BaseCustomException {
    public InsufficientStockException(Throwable cause) {
        super(INSUFFICIENT_STOCK, cause);
    }

    public InsufficientStockException() {
        super(INSUFFICIENT_STOCK);
    }
}