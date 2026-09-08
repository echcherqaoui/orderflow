package com.echcherqaoui.orderflow.order.service;

import com.echcherqaoui.orderflow.order.model.OrderSagaHistory;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import com.echcherqaoui.orderflow.order.repository.OrderSagaHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.ORDER_CREATED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.COMPLETED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.FAILED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class SagaStepLoggerTest {

    @Mock
    private OrderSagaHistoryRepository sagaHistoryRepository;

    @InjectMocks
    private SagaStepLogger sagaStepLogger;

    @Captor
    private ArgumentCaptor<OrderSagaHistory> historyCaptor;

    private final UUID orderId = UUID.randomUUID();
    private final SagaStep step = INVENTORY_RESERVED;
    private final SagaStepStatus status = COMPLETED;
    private final String triggerEvent = "PaymentCompletedEvent";
    private final String triggerEventId = UUID.randomUUID().toString();
    private final Map<String, Object> metadata = Map.of("amountCents", 9900L);

    @Nested
    @DisplayName("StepLog factory methods")
    class StepLogFactory {

        @Test
        @DisplayName("started() creates StepLog with STARTED status")
        void started_createsStepLogWithStartedStatus() {
            SagaStepLogger.StepLog stepLog = SagaStepLogger.StepLog.started(ORDER_CREATED, metadata);

            assertThat(stepLog.step()).isEqualTo(ORDER_CREATED);
            assertThat(stepLog.status()).isEqualTo(STARTED);
            assertThat(stepLog.metadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("completed() creates StepLog with COMPLETED status")
        void completed_createsStepLogWithCompletedStatus() {
            SagaStepLogger.StepLog stepLog = SagaStepLogger.StepLog.completed(INVENTORY_RESERVED, metadata);

            assertThat(stepLog.step()).isEqualTo(INVENTORY_RESERVED);
            assertThat(stepLog.status()).isEqualTo(COMPLETED);
            assertThat(stepLog.metadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("failed() creates StepLog with FAILED status")
        void failed_createsStepLogWithFailedStatus() {
            SagaStepLogger.StepLog stepLog = SagaStepLogger.StepLog.failed(INVENTORY_RESERVED, metadata);

            assertThat(stepLog.step()).isEqualTo(INVENTORY_RESERVED);
            assertThat(stepLog.status()).isEqualTo(FAILED);
            assertThat(stepLog.metadata()).isEqualTo(metadata);
        }
    }

    @Nested
    @DisplayName("logStep()")
    class LogStep {

        @Test
        @DisplayName("invocation with metadata builds OrderSagaHistory and persists to repository")
        void logStep_withMetadata_buildsAndSavesHistory() {
            given(sagaHistoryRepository.save(any(OrderSagaHistory.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            sagaStepLogger.logStep(orderId, step, status, triggerEvent, triggerEventId, metadata);

            then(sagaHistoryRepository).should().save(historyCaptor.capture());
            OrderSagaHistory savedHistory = historyCaptor.getValue();

            assertThat(savedHistory.getOrderId()).isEqualTo(orderId);
            assertThat(savedHistory.getStep()).isEqualTo(step);
            assertThat(savedHistory.getStatus()).isEqualTo(status);
            assertThat(savedHistory.getTriggerEvent()).isEqualTo(triggerEvent);
            assertThat(savedHistory.getTriggerEventId()).isEqualTo(triggerEventId);
            assertThat(savedHistory.getMetadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("overloaded invocation without metadata sets null metadata and persists to repository")
        void logStep_withoutMetadata_buildsAndSavesHistoryWithNullMetadata() {
            given(sagaHistoryRepository.save(any(OrderSagaHistory.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            sagaStepLogger.logStep(orderId, step, status, triggerEvent, triggerEventId);

            then(sagaHistoryRepository).should().save(historyCaptor.capture());
            OrderSagaHistory savedHistory = historyCaptor.getValue();

            assertThat(savedHistory.getOrderId()).isEqualTo(orderId);
            assertThat(savedHistory.getStep()).isEqualTo(step);
            assertThat(savedHistory.getStatus()).isEqualTo(status);
            assertThat(savedHistory.getTriggerEvent()).isEqualTo(triggerEvent);
            assertThat(savedHistory.getTriggerEventId()).isEqualTo(triggerEventId);
            assertThat(savedHistory.getMetadata()).isNull();
        }

        @Test
        @DisplayName("repository save failure propagates exception")
        void logStep_repositorySaveFails_propagatesException() {
            RuntimeException dbException = new RuntimeException("Database connection error");
            given(sagaHistoryRepository.save(any(OrderSagaHistory.class)))
                  .willThrow(dbException);

            assertThatThrownBy(() -> sagaStepLogger.logStep(orderId, step, status, triggerEvent, triggerEventId, metadata))
                  .isSameAs(dbException);

            then(sagaHistoryRepository).should().save(any(OrderSagaHistory.class));
        }
    }

    @Nested
    @DisplayName("logTransition()")
    class LogTransition {

        @Test
        @DisplayName("both closed and opened logs persist two saga history entries in sequence")
        void logTransition_bothClosedAndOpened_savesBothEntries() {
            given(sagaHistoryRepository.save(any(OrderSagaHistory.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            SagaStepLogger.StepLog closed = SagaStepLogger.StepLog.completed(ORDER_CREATED, Map.of("phase", "init"));
            SagaStepLogger.StepLog opened = SagaStepLogger.StepLog.started(INVENTORY_RESERVED, Map.of("phase", "reserve"));

            sagaStepLogger.logTransition(orderId, closed, opened, triggerEvent, triggerEventId);

            then(sagaHistoryRepository).should(times(2)).save(historyCaptor.capture());
            List<OrderSagaHistory> saved = historyCaptor.getAllValues();

            OrderSagaHistory closedHistory = saved.get(0);
            assertThat(closedHistory.getOrderId()).isEqualTo(orderId);
            assertThat(closedHistory.getStep()).isEqualTo(ORDER_CREATED);
            assertThat(closedHistory.getStatus()).isEqualTo(COMPLETED);
            assertThat(closedHistory.getTriggerEvent()).isEqualTo(triggerEvent);
            assertThat(closedHistory.getTriggerEventId()).isEqualTo(triggerEventId);
            assertThat(closedHistory.getMetadata()).isEqualTo(Map.of("phase", "init"));

            OrderSagaHistory openedHistory = saved.get(1);
            assertThat(openedHistory.getOrderId()).isEqualTo(orderId);
            assertThat(openedHistory.getStep()).isEqualTo(INVENTORY_RESERVED);
            assertThat(openedHistory.getStatus()).isEqualTo(STARTED);
            assertThat(openedHistory.getTriggerEvent()).isEqualTo(triggerEvent);
            assertThat(openedHistory.getTriggerEventId()).isEqualTo(triggerEventId);
            assertThat(openedHistory.getMetadata()).isEqualTo(Map.of("phase", "reserve"));
        }

        @Test
        @DisplayName("only closed log persists one closed saga history entry")
        void logTransition_onlyClosed_savesClosedEntry() {
            given(sagaHistoryRepository.save(any(OrderSagaHistory.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            SagaStepLogger.StepLog closed = SagaStepLogger.StepLog.failed(INVENTORY_RESERVED, metadata);

            sagaStepLogger.logTransition(orderId, closed, null, triggerEvent, triggerEventId);

            then(sagaHistoryRepository).should().save(historyCaptor.capture());
            OrderSagaHistory savedHistory = historyCaptor.getValue();

            assertThat(savedHistory.getOrderId()).isEqualTo(orderId);
            assertThat(savedHistory.getStep()).isEqualTo(INVENTORY_RESERVED);
            assertThat(savedHistory.getStatus()).isEqualTo(FAILED);
            assertThat(savedHistory.getMetadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("only opened log persists one opened saga history entry")
        void logTransition_onlyOpened_savesOpenedEntry() {
            given(sagaHistoryRepository.save(any(OrderSagaHistory.class)))
                  .willAnswer(invocation -> invocation.getArgument(0));

            SagaStepLogger.StepLog opened = SagaStepLogger.StepLog.started(ORDER_CREATED, metadata);

            sagaStepLogger.logTransition(orderId, null, opened, triggerEvent, triggerEventId);

            then(sagaHistoryRepository).should().save(historyCaptor.capture());
            OrderSagaHistory savedHistory = historyCaptor.getValue();

            assertThat(savedHistory.getOrderId()).isEqualTo(orderId);
            assertThat(savedHistory.getStep()).isEqualTo(ORDER_CREATED);
            assertThat(savedHistory.getStatus()).isEqualTo(STARTED);
            assertThat(savedHistory.getMetadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("both closed and opened null saves no saga history entries")
        void logTransition_bothNull_savesNothing() {
            sagaStepLogger.logTransition(orderId, null, null, triggerEvent, triggerEventId);

            then(sagaHistoryRepository).should(never()).save(any());
        }
    }
}