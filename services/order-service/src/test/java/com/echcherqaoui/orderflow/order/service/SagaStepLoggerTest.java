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

import java.util.Map;
import java.util.UUID;

import static com.echcherqaoui.orderflow.order.model.enums.SagaStep.INVENTORY_RESERVED;
import static com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
    private final SagaStepStatus status = SUCCEEDED;
    private final String triggerEvent = "PaymentCompletedEvent";
    private final String triggerEventId = UUID.randomUUID().toString();
    private final Map<String, Object> metadata = Map.of("amountCents", 9900L);

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
}