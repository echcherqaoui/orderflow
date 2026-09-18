package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.model.ProcessedWebhookEvent;
import com.echcherqaoui.orderflow.payment.repository.ProcessedWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookIdempotencyService {

    private final ProcessedWebhookEventRepository processedWebhookEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerEvent(String eventId, String eventType) {
        ProcessedWebhookEvent event = new ProcessedWebhookEvent()
              .setEventId(eventId)
              .setEventType(eventType);

        // If duplicate, this throws DataIntegrityViolationException.
        // Transaction rolls back cleanly!
        processedWebhookEventRepository.saveAndFlush(event);
    }
}