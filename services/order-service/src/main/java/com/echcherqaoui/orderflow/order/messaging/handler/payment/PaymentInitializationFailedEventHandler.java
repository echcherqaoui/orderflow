package com.echcherqaoui.orderflow.order.messaging.handler.payment;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitializationFailedEvent;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.order.service.OrderSagaService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PaymentInitializationFailedEventHandler implements EventHandler<PaymentInitializationFailedEvent> {

    private final OrderSagaService orderSagaService;

    @Override
    public String getDescriptorFullName() {
        return PaymentInitializationFailedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@lombok.NonNull PaymentInitializationFailedEvent event,
                                    @lombok.NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              event.getReason(),
              String.valueOf(metadata.getOccurredAt().getSeconds())
        );
    }

    @Override
    public void handle(@lombok.NonNull PaymentInitializationFailedEvent event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        orderSagaService.handlePaymentInitializationFailed(
              orderId,
              event.getReason(),
              event.getMetadata().getMessageId()
        );
    }
}