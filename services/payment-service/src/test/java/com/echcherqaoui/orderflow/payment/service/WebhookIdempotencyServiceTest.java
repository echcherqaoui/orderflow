package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.model.ProcessedWebhookEvent;
import com.echcherqaoui.orderflow.payment.repository.ProcessedWebhookEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyServiceTest {

    @Mock
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    @InjectMocks
    private WebhookIdempotencyService webhookIdempotencyService;

    @Captor
    private ArgumentCaptor<ProcessedWebhookEvent> eventCaptor;

    private final String eventId = "evt_123456";
    private final String eventType = "payment_intent.succeeded";

    @Test
    @DisplayName("registerEvent() creates and saves entity to repository")
    void registerEvent_success_savesEntity() {
        given(processedWebhookEventRepository.saveAndFlush(any(ProcessedWebhookEvent.class)))
              .willAnswer(invocation -> invocation.getArgument(0));

        webhookIdempotencyService.registerEvent(eventId, eventType);

        then(processedWebhookEventRepository).should().saveAndFlush(eventCaptor.capture());
        ProcessedWebhookEvent savedEvent = eventCaptor.getValue();

        assertThat(savedEvent).isNotNull();
        assertThat(savedEvent.getEventId()).isEqualTo(eventId);
        assertThat(savedEvent.getEventType()).isEqualTo(eventType);
    }

    @Test
    @DisplayName("registerEvent() propagates DataIntegrityViolationException on duplicate eventId")
    void registerEvent_duplicateEventId_propagatesException() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException("Duplicate PK");

        given(processedWebhookEventRepository.saveAndFlush(any(ProcessedWebhookEvent.class)))
              .willThrow(exception);

        assertThatThrownBy(() -> webhookIdempotencyService.registerEvent(eventId, eventType))
              .isSameAs(exception);

        then(processedWebhookEventRepository).should().saveAndFlush(any(ProcessedWebhookEvent.class));
    }
}