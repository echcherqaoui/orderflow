    package com.echcherqaoui.orderflow.order.messaging.handler.entitlement;

    import com.echcherqaoui.orderflow.contracts.common.v1.MessageMetadata;
    import com.echcherqaoui.orderflow.contracts.entitlement.events.v1.EntitlementGrantedEvent;
    import com.echcherqaoui.orderflow.kafka.EventHandler;
    import com.echcherqaoui.orderflow.order.service.OrderSagaService;
    import com.echcherqaoui.orderflow.security.service.SignatureService;
    import lombok.RequiredArgsConstructor;
    import org.jspecify.annotations.NonNull;
    import org.springframework.stereotype.Component;

    import java.util.UUID;
    import java.util.stream.Stream;

    @Component
    @RequiredArgsConstructor
    public class EntitlementGrantedHandler implements EventHandler<EntitlementGrantedEvent> {

        private final OrderSagaService orderSagaService;

        @Override
        public String getDescriptorFullName() {
            return EntitlementGrantedEvent.getDescriptor().getFullName();
        }

        @Override
        public boolean isSignatureValid(@NonNull EntitlementGrantedEvent event,
                                        @NonNull SignatureService signatureService) {
            MessageMetadata metadata = event.getMetadata();

            String[] signatureParams = Stream.concat(
                  Stream.of(
                        metadata.getMessageId(),
                        metadata.getCorrelationId(),
                        String.valueOf(metadata.getOccurredAt().getSeconds()),
                        event.getUserId()
                  ),
                  event.getAccessKeysList().stream()
            ).toArray(String[]::new);

            return signatureService.verify(
                  metadata.getSignature(),
                  signatureParams
            );
        }

        @Override
        public void handle(@lombok.NonNull EntitlementGrantedEvent event) {
            UUID orderId = UUID.fromString(event.getMetadata().getCorrelationId());

            orderSagaService.handleEntitlementGranted(
                  orderId,
                  event.getMetadata().getMessageId()
            );
        }
    }