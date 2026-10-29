package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.common.outbox.repository.OutboxEventRepository;
import com.echcherqaoui.orderflow.entitlement.exception.domain.KeyPoolExhaustedException;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import com.echcherqaoui.orderflow.entitlement.util.ItemIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class EntitlementAllocationServiceIT implements WithPostgres {

    @Autowired
    private EntitlementAllocationService allocationService;

    @Autowired
    private LicenseKeyRepository licenseKeyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private UUID itemId;
    private String itemName;

    @BeforeEach
    void setUp() {
        licenseKeyRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        itemName = "test-product";
        itemId = ItemIds.fromName(itemName);
    }

    @Test
    @DisplayName("allocate successfully claims available key, updates entity state, and saves outbox event")
    @Transactional
    void allocate_success_claimsKeyAndSavesOutbox() {
        UUID orderId = UUID.randomUUID();
        String userId = "user-123";
        String triggerEventId = UUID.randomUUID().toString();

        LicenseKey key = new LicenseKey()
              .setItemId(itemId)
              .setItemName(itemName)
              .setKeyCode("KEY-PROD-999")
              .setStatus(LicenseKeyStatus.AVAILABLE)
              .setPriceCents(2500L);

        licenseKeyRepository.saveAndFlush(key);

        allocationService.allocate(List.of(itemId.toString()), orderId, userId, triggerEventId);

        // Verify key state updated
        LicenseKey updatedKey = licenseKeyRepository.findById(key.getId()).orElseThrow();
        assertThat(updatedKey.getStatus()).isEqualTo(LicenseKeyStatus.ASSIGNED);
        assertThat(updatedKey.getOrderId()).isEqualTo(orderId);
        assertThat(updatedKey.getUserId()).isEqualTo(userId);
        assertThat(updatedKey.getAssignedAt()).isNotNull();

        // Verify outbox event created
        long outboxCount = outboxEventRepository.count();
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("allocate throws KeyPoolExhaustedException when no available keys exist")
    @Transactional
    void allocate_poolExhausted_throwsException() {
        UUID orderId = UUID.randomUUID();
        String userId = "user-123";
        String triggerEventId = UUID.randomUUID().toString();

        // No keys inserted in DB for itemId

        assertThatThrownBy(() -> allocationService.allocate(
              List.of(itemId.toString()), orderId, userId, triggerEventId
        )).isInstanceOf(KeyPoolExhaustedException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }
}