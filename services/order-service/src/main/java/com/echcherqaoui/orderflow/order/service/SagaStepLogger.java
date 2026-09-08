package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.model.OrderSagaHistory;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import com.echcherqaoui.orderflow.order.repository.OrderSagaHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.COMPLETED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.FAILED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.STARTED;

/**
 * Every orchestrator transaction inserts exactly one row here alongside its
 * entity update and outbox write (insert-only audit trail — no updates, per doc).
 */
@Component
@RequiredArgsConstructor
public class SagaStepLogger {

    private final OrderSagaHistoryRepository sagaHistoryRepository;

    public record StepLog(SagaStep step, SagaStepStatus status, Map<String, Object> metadata) {
        public static StepLog started(SagaStep step, Map<String, Object> metadata) {
            return new StepLog(step, STARTED, metadata);
        }

        public static StepLog completed(SagaStep step, Map<String, Object> metadata) {
            return new StepLog(step, COMPLETED, metadata);
        }

        public static StepLog failed(SagaStep step, Map<String, Object> metadata) {
            return new StepLog(step, FAILED, metadata);
        }
    }

    public void logStep(UUID orderId,
                        SagaStep step,
                        SagaStepStatus status,
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
        logStep(orderId, step, status, triggerEvent, triggerEventId, null);
    }

    /**
     * Atomically logs the completion/failure of an active step and/or the start of a new step
     * within the current transaction.
     */
    public void logTransition(UUID orderId,
                              StepLog closed,
                              StepLog opened,
                              String triggerEvent,
                              String triggerEventId) {
        if (closed != null)
            logStep(orderId, closed.step(), closed.status(), triggerEvent, triggerEventId, closed.metadata());

        if (opened != null)
            logStep(orderId, opened.step(), opened.status(), triggerEvent, triggerEventId, opened.metadata());
    }
}