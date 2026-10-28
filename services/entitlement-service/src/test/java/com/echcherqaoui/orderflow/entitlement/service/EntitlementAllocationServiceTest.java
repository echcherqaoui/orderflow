package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateOrderItemsException;
import com.echcherqaoui.orderflow.entitlement.exception.domain.EmptyOrderException;
import com.echcherqaoui.orderflow.entitlement.exception.domain.KeyPoolExhaustedException;
import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.ASSIGNED;
import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.AVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class EntitlementAllocationServiceTest {

    @Mock
    private LicenseKeyRepository licenseKeyRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private EntitlementAllocationService allocationService;

    @Test
    @DisplayName("allocate with null or empty itemIds throws EmptyOrderException")
    void allocate_emptyItemIds_throwsException() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> allocationService.allocate(null, orderId, "user-1", "trig-1"))
              .isInstanceOf(EmptyOrderException.class);

        assertThatThrownBy(() -> allocationService.allocate(List.of(), orderId, "user-1", "trig-1"))
              .isInstanceOf(EmptyOrderException.class);
    }

    @Test
    @DisplayName("allocate with duplicate itemIds throws DuplicateOrderItemsException")
    void allocate_duplicateItemIds_throwsException() {
        UUID orderId = UUID.randomUUID();
        String itemIdStr = UUID.randomUUID().toString();
        List<String> duplicateIds = List.of(itemIdStr, itemIdStr);

        assertThatThrownBy(() -> allocationService.allocate(duplicateIds, orderId, "user-1", "trig-1"))
              .isInstanceOf(DuplicateOrderItemsException.class);
    }

    @Test
    @DisplayName("allocate with exhausted key pool throws KeyPoolExhaustedException")
    void allocate_exhaustedPool_throwsException() {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        List<String> itemIds = List.of(itemId.toString());

        given(licenseKeyRepository.findFirstByItemIdAndStatusOrderByIdAsc(itemId, AVAILABLE))
              .willReturn(Optional.empty());

        assertThatThrownBy(() -> allocationService.allocate(itemIds, orderId, "user-1", "trig-1"))
              .isInstanceOf(KeyPoolExhaustedException.class);
    }

    @Test
    @DisplayName("allocate with available keys updates status, flushes, and writes outbox event")
    void allocate_success_claimsKeysAndPublishesEvent() {
        UUID orderId = UUID.randomUUID();
        String userId = "user-123";
        String triggerEventId = "trig-123";
        UUID itemId = UUID.randomUUID();

        LicenseKey key = new LicenseKey()
              .setItemId(itemId)
              .setItemName("test-item")
              .setKeyCode("KEY-ABC-123")
              .setStatus(AVAILABLE)
              .setPriceCents(1000L);
        key.setId(1L);

        given(licenseKeyRepository.findFirstByItemIdAndStatusOrderByIdAsc(itemId, AVAILABLE))
              .willReturn(Optional.of(key));

        allocationService.allocate(List.of(itemId.toString()), orderId, userId, triggerEventId);

        assertThat(key.getStatus()).isEqualTo(ASSIGNED);
        assertThat(key.getOrderId()).isEqualTo(orderId);
        assertThat(key.getUserId()).isEqualTo(userId);
        assertThat(key.getAssignedAt()).isNotNull();

        then(licenseKeyRepository).should().flush();
        then(outboxWriter).should().writeEntitlementGranted(
              orderId,
              userId,
              List.of("KEY-ABC-123"),
              triggerEventId
        );
    }
}