package com.echcherqaoui.orderflow.order.exception.code;

import com.echcherqaoui.orderflow.exception.core.IErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements IErrorCode {
    ORDER_NOT_FOUND("ORDER_404", "Order with ID: %s could not be found.", 404),
    ITEM_NOT_FOUND("ITEM_404", "One or more requested items could not be found.", 404),
    CART_ALREADY_PROCESSED("CART_409", "An order has already been processed for cart ID: %s", 409),
    INSUFFICIENT_STOCK("STOCK_409", "One or more requested items are out of stock.", 409);

    private final String code;
    private final String message;
    private final int httpStatus;
}
