package com.echcherqaoui.orderflow.payment.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.RefundPaymentCommand;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.payment.service.PaymentService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RefundPaymentCommandHandler implements EventHandler<RefundPaymentCommand> {

    private final PaymentService paymentService;

    @Override
    public String getDescriptorFullName() {
        return RefundPaymentCommand.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull RefundPaymentCommand event,
                                    @NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getOrderId(),
              event.getPaymentIntentId(),
              event.getReason()
        );
    }

    @Override
    public void handle(@NonNull RefundPaymentCommand event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        paymentService.processRefund(
              orderId,
              event.getPaymentIntentId(),
              event.getReason(),
              event.getMetadata().getMessageId()
        );
    }
}
