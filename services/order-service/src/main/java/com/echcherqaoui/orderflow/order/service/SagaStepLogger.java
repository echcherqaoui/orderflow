package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.model.OrderSagaHistory;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import com.echcherqaoui.orderflow.order.repository.OrderSagaHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Every orchestrator transaction inserts exactly one row here alongside its
 * entity update and outbox write (insert-only audit trail — no updates, per doc).
 */
@Component
@RequiredArgsConstructor
public class SagaStepLogger {

    private final OrderSagaHistoryRepository sagaHistoryRepository;

    public void logStep(UUID orderId,
                        SagaStep step, SagaStepStatus status,
                        String triggerEvent,
                        String triggerEventId,
                        Map<String, Object> metadata) {
        OrderSagaHistory history = new OrderSagaHistory()
              .setOrderId(orderId)
              .setStep(step)
              .setStatus(status)
              .setTriggerEvent(triggerEvent)
              .setTriggerEventId(triggerEventId)
              .setMetadata(metadata);

        sagaHistoryRepository.save(history);
    }

    public void logStep(UUID orderId,
                        SagaStep step,
                        SagaStepStatus status,
                        String triggerEvent,
                        String triggerEventId) {
        logStep(
              orderId,
              step,
              status,
              triggerEvent,
              triggerEventId,
              null
        );
    }
}
