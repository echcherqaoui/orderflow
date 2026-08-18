package com.echcherqaoui.orderflow.exception.core;


import static com.echcherqaoui.orderflow.exception.core.CommonErrorCode.EVENT_PROCESSING_FAILED;

public class EventProcessingException extends BaseCustomException {
    public EventProcessingException(IErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public EventProcessingException(Object... args) {
        super(EVENT_PROCESSING_FAILED, args);
    }
}