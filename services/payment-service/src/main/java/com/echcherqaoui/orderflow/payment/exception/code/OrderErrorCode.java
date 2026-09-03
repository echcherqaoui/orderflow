package com.echcherqaoui.orderflow.payment.exception.code;

import com.echcherqaoui.orderflow.exception.core.IErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements IErrorCode {
    PSP_CONNECTION_FAILED("SPS_503", "PSP connection failed: HTTP 503 Service Unavailable.", 503);

    private final String code;
    private final String message;
    private final int httpStatus;
}
