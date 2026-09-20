package com.echcherqaoui.orderflow.order.messaging.handler.payment;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.events.v1.PaymentInitiatedEvent;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.order.service.OrderSagaService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PaymentInitiatedEventHandler implements EventHandler<PaymentInitiatedEvent> {

    private final OrderSagaService orderSagaService;

    @Override
    public String getDescriptorFullName() {
        return PaymentInitiatedEvent.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@lombok.NonNull PaymentInitiatedEvent event,
                                    @lombok.NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getPaymentIntentId()
        );
    }

    @Override
    public void handle(@lombok.NonNull PaymentInitiatedEvent event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        orderSagaService.handlePaymentInitiated(
              orderId,
              event.getPaymentIntentId(),
              event.getClientSecret(),
              event.getMetadata().getMessageId()
        );
    }
}