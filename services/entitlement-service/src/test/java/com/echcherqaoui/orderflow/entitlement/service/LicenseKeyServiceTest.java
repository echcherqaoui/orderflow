package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionResponse;
import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateKeysException;
import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import com.echcherqaoui.orderflow.entitlement.util.ItemIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.AVAILABLE;
import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.PENDING_PROVISION;
import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.PROVISION_REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LicenseKeyServiceTest {

    @Mock
    private LicenseKeyRepository licenseKeyRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private LicenseKeyService licenseKeyService;

    private final String itemName = "prod-123";
    UUID itemId = ItemIds.fromName(itemName);

    private final Long priceCents = 1999L;

    @Nested
    @DisplayName("ingestKeys()")
    class IngestKeys {

        @Test
        @DisplayName("null request throws NullPointerException")
        void nullRequest_throwsNullPointerException() {
            assertThatThrownBy(() -> licenseKeyService.ingestKeys(null))
                  .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("duplicate keys within the same payload throw DuplicateKeysException")
        void duplicateKeysInPayload_throwsDuplicateKeysException() {
            List<String> keys = List.of("KEY-01", "KEY-02", "KEY-01");
            BatchIngestionRequest request = new BatchIngestionRequest(itemName, priceCents, keys);

            assertThatThrownBy(() -> licenseKeyService.ingestKeys(request))
                  .isInstanceOf(DuplicateKeysException.class);

            then(licenseKeyRepository).shouldHaveNoInteractions();
            then(outboxWriter).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("keys already existing in database throw DuplicateKeysException")
        void existingKeysInDatabase_throwsDuplicateKeysException() {
            List<String> keys = List.of("KEY-01", "KEY-02");
            BatchIngestionRequest request = new BatchIngestionRequest(itemName, priceCents, keys);

            given(licenseKeyRepository.findExistingKeyCodes(keys)).willReturn(List.of("KEY-01"));

            assertThatThrownBy(() -> licenseKeyService.ingestKeys(request))
                  .isInstanceOf(DuplicateKeysException.class);

            then(licenseKeyRepository).should().findExistingKeyCodes(keys);
            then(licenseKeyRepository).should(org.mockito.Mockito.never()).saveAll(anyList());
            then(outboxWriter).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("valid request saves keys, publishes outbox event, and returns response")
        void validRequest_savesKeysAndPublishesOutboxEvent() {
            List<String> keys = List.of("KEY-01", "KEY-02");
            BatchIngestionRequest request = new BatchIngestionRequest(itemName, priceCents, keys);

            given(licenseKeyRepository.findExistingKeyCodes(keys)).willReturn(List.of());

            BatchIngestionResponse response = licenseKeyService.ingestKeys(request);

            assertThat(response).isNotNull();
            assertThat(response.itemName()).isEqualTo(itemName);
            assertThat(response.keysAdded()).isEqualTo(2);

            then(licenseKeyRepository).should().findExistingKeyCodes(keys);
            then(licenseKeyRepository).should().saveAll(anyList());
            then(outboxWriter).should().publishDigitalStockProvisionedEvent(itemId, itemName, priceCents, 2);
        }
    }

    @Nested
    @DisplayName("completeProvisioning()")
    class CompleteProvisioning {

        @Test
        @DisplayName("updates status and logs info when updated count matches expected quantity")
        void completeProvisioning_matchingCount_updatesSuccessfully() {
            given(licenseKeyRepository.updateStatusForProduct(itemName, PENDING_PROVISION, AVAILABLE))
                  .willReturn(5);

            licenseKeyService.completeProvisioning(itemName, 5);

            then(licenseKeyRepository).should().updateStatusForProduct(itemName, PENDING_PROVISION, AVAILABLE);
        }

        @Test
        @DisplayName("updates status and logs warning when updated count does not match expected quantity")
        void completeProvisioning_mismatchCount_logsWarning() {
            given(licenseKeyRepository.updateStatusForProduct(itemName, PENDING_PROVISION, AVAILABLE))
                  .willReturn(3);

            licenseKeyService.completeProvisioning(itemName, 5);

            then(licenseKeyRepository).should().updateStatusForProduct(itemName, PENDING_PROVISION, AVAILABLE);
        }
    }

    @Nested
    @DisplayName("rejectProvisioning()")
    class RejectProvisioning {

        @Test
        @DisplayName("updates status to PROVISION_REJECTED successfully when pending keys exist")
        void rejectProvisioning_updatesStatusSuccessfully() {
            given(licenseKeyRepository.updateStatusForProduct(itemName, PENDING_PROVISION, PROVISION_REJECTED))
                  .willReturn(4);

            licenseKeyService.rejectProvisioning(itemName, "Out of stock");

            then(licenseKeyRepository).should().updateStatusForProduct(itemName, PENDING_PROVISION, PROVISION_REJECTED);
        }
    }
}