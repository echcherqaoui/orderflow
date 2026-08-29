package com.echcherqaoui.orderflow.order.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;

import static com.echcherqaoui.orderflow.order.exception.code.OrderErrorCode.CART_ALREADY_PROCESSED;

public class CartAlreadyProcessedException extends BaseCustomException {

    public CartAlreadyProcessedException(String cartId, Throwable cause) {
        super(CART_ALREADY_PROCESSED, cause, cartId);
    }

    public CartAlreadyProcessedException(String cartId) {
        super(CART_ALREADY_PROCESSED, cartId);
    }
}