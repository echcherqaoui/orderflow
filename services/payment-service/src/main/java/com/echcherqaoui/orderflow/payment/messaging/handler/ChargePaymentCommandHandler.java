package com.echcherqaoui.orderflow.payment.messaging.handler;

import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
import com.echcherqaoui.orderflow.contracts.payment.commands.v1.ChargePaymentCommand;
import com.echcherqaoui.orderflow.kafka.EventHandler;
import com.echcherqaoui.orderflow.payment.service.PaymentInitializationService;
import com.echcherqaoui.orderflow.security.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChargePaymentCommandHandler implements EventHandler<ChargePaymentCommand> {

    private final PaymentInitializationService paymentInitializationService;

    @Override
    public String getDescriptorFullName() {
        return ChargePaymentCommand.getDescriptor().getFullName();
    }

    @Override
    public boolean isSignatureValid(@NonNull ChargePaymentCommand event,
                                    @NonNull SignatureService signatureService) {
        MessageMetadata metadata = event.getMetadata();

        return signatureService.verify(
              metadata.getSignature(),
              metadata.getMessageId(),
              metadata.getCorrelationId(),
              String.valueOf(metadata.getOccurredAt().getSeconds()),
              event.getUserId(),
              String.valueOf(event.getTotalPriceCents())
        );
    }

    @Override
    public void handle(@NonNull ChargePaymentCommand event) {
        UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

        paymentInitializationService.initializePayment(
              orderId,
              event.getUserId(),
              event.getTotalPriceCents(),
              event.getMetadata().getMessageId()
        );
    }
}
