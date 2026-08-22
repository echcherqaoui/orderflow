package com.echcherqaoui.orderflow.inventory.exception.domain;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;

public class InvalidReservationException extends BaseCustomException {
    public InvalidReservationException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
