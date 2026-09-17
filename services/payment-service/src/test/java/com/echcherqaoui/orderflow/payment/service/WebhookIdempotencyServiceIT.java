package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.model.ProcessedWebhookEvent;
import com.echcherqaoui.orderflow.payment.repository.ProcessedWebhookEventRepository;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(WebhookIdempotencyService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookIdempotencyServiceIT implements WithPostgres {

    @Autowired
    private WebhookIdempotencyService webhookIdempotencyService;

    @Autowired
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    private final String eventId = "evt_stripe_123456";
    private final String eventType = "payment_intent.succeeded";

    @AfterEach
    void tearDown() {
        processedWebhookEventRepository.deleteAllInBatch();
    }

    @Nested
    @DisplayName("registerEvent()")
    class RegisterEvent {

        @Test
        @DisplayName("persists processed webhook event successfully in database")
        void registerEvent_success_persistsEvent() {
            webhookIdempotencyService.registerEvent(eventId, eventType);

            Optional<ProcessedWebhookEvent> result = processedWebhookEventRepository.findById(eventId);

            assertThat(result).isPresent();
            assertThat(result.get().getEventId()).isEqualTo(eventId);
            assertThat(result.get().getEventType()).isEqualTo(eventType);
            assertThat(result.get().getProcessedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws DataIntegrityViolationException when duplicate eventId is inserted")
        void registerEvent_duplicateEventId_throwsDataIntegrityViolationException() {
            webhookIdempotencyService.registerEvent(eventId, eventType);

            assertThatThrownBy(() -> webhookIdempotencyService.registerEvent(eventId, eventType))
                  .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}