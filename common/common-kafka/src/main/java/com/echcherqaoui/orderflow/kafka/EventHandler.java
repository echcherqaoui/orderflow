package com.echcherqaoui.orderflow.kafka;

import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.google.protobuf.Message;

public interface EventHandler<T extends Message> {
    String getDescriptorFullName();
    boolean isSignatureValid(T event, SignatureService signatureService);
    void handle(T event);
}