package com.echcherqaoui.orderflow.exception.core;

import static com.echcherqaoui.orderflow.exception.core.CommonErrorCode.CONFLICT;

public class ConflictException extends BaseCustomException {
    public ConflictException(String reason) {
        super(CONFLICT, reason);
    }
}
