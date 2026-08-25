package com.echcherqaoui.orderflow.common.outbox.repository;

import com.echcherqaoui.orderflow.common.outbox.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.common.outbox.model.OutboxEvent;
import com.echcherqaoui.orderflow.common.outbox.model.OutboxEventId;
import com.echcherqaoui.orderflow.common.outbox.partition.OutboxPartitionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // Ensure active partition exists for inserting data
        OutboxPartitionManager partitionManager = new OutboxPartitionManager(jdbcTemplate, 7, 2);
        transactionTemplate.executeWithoutResult(status -> partitionManager.ensurePartitionsExist());
    }

    @Test
    @DisplayName("OutboxEvent persists correctly and can be queried using composite key")
    void saveAndFindById_validEntity_persistsSuccessfully() {
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        byte[] payloadBytes = "{\"orderId\":\"123\", \"status\":\"CREATED\"}".getBytes(StandardCharsets.UTF_8);

        OutboxEvent event = new OutboxEvent()
              .setId(eventId)
              .setAggregateType("ORDER")
              .setAggregateId("123")
              .setEventType("ORDER_CREATED")
              .setPayload(payloadBytes)
              .setCreatedAt(createdAt);

        repository.saveAndFlush(event);

        OutboxEventId compositeKey = new OutboxEventId(eventId, createdAt);
        Optional<OutboxEvent> found = repository.findById(compositeKey);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(eventId);
        assertThat(found.get().getAggregateType()).isEqualTo("ORDER");
        assertThat(found.get().getPayload()).isEqualTo(payloadBytes);
    }
}