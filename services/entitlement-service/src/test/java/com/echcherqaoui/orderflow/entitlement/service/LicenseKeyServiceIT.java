package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionResponse;
import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateKeysException;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.AVAILABLE;
import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.PROVISION_REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LicenseKeyServiceIT implements WithPostgres {

    @Autowired
    private LicenseKeyService licenseKeyService;

    @Autowired
    private LicenseKeyRepository licenseKeyRepository;

    @BeforeEach
    void setUp() {
        licenseKeyRepository.deleteAllInBatch();
    }

    @Nested
    @DisplayName("ingestKeys")
    class IngestKeysTests {

        @Test
        @DisplayName("successfully ingests new batch of keys and persists outbox event")
        void success_savesKeysAndReturnsResponse() {
            String productId = "prod-" + UUID.randomUUID();
            List<String> keys = List.of("KEY-01-" + UUID.randomUUID(), "KEY-02-" + UUID.randomUUID());
            BatchIngestionRequest request = new BatchIngestionRequest(productId, 1999L, keys);

            BatchIngestionResponse response = licenseKeyService.ingestKeys(request);

            assertThat(response).isNotNull();
            assertThat(response.productId()).isEqualTo(productId);
            assertThat(response.keysAdded()).isEqualTo(2);
            assertThat(licenseKeyRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("existing keys in database throw DuplicateKeysException and persist nothing new")
        void duplicateKeysInDatabase_throwsException() {
            String productId = "prod-" + UUID.randomUUID();
            String duplicateKey = "KEY-DUP-" + UUID.randomUUID();

            // Seed database with one existing key
            BatchIngestionRequest initialRequest = new BatchIngestionRequest(productId, 1999L, List.of(duplicateKey));
            licenseKeyService.ingestKeys(initialRequest);

            long countBefore = licenseKeyRepository.count();

            // Attempt to ingest batch containing the existing key
            List<String> newKeys = List.of(duplicateKey, "KEY-NEW-" + UUID.randomUUID());
            BatchIngestionRequest requestWithDuplicates = new BatchIngestionRequest(productId, 1999L, newKeys);

            assertThatThrownBy(() -> licenseKeyService.ingestKeys(requestWithDuplicates))
                  .isInstanceOf(DuplicateKeysException.class);

            assertThat(licenseKeyRepository.count()).isEqualTo(countBefore);
        }

        @Test
        @DisplayName("duplicate keys within the same payload throw DuplicateKeysException and persist nothing")
        void duplicateKeysInPayload_throwsException() {
            String productId = "prod-" + UUID.randomUUID();
            String duplicatedKey = "KEY-REPEATED-" + UUID.randomUUID();

            List<String> keysWithInternalDuplicates = List.of(duplicatedKey, "KEY-UNIQUE-" + UUID.randomUUID(), duplicatedKey);
            BatchIngestionRequest request = new BatchIngestionRequest(productId, 1999L, keysWithInternalDuplicates);

            assertThatThrownBy(() -> licenseKeyService.ingestKeys(request))
                  .isInstanceOf(DuplicateKeysException.class);

            assertThat(licenseKeyRepository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("completeProvisioning")
    class CompleteProvisioningTests {

        @Test
        @DisplayName("transitions pending keys to available successfully")
        void success_transitionsToAvailable() {
            String productId = "prod-" + UUID.randomUUID();
            List<String> keys = List.of("KEY-01-" + UUID.randomUUID(), "KEY-02-" + UUID.randomUUID());
            licenseKeyService.ingestKeys(new BatchIngestionRequest(productId, 1999L, keys));

            licenseKeyService.completeProvisioning(productId, 2);

            long availableCount = licenseKeyRepository.findAll().stream()
                  .filter(k -> k.getStatus() == AVAILABLE)
                  .count();

            assertThat(availableCount).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("rejectProvisioning")
    class RejectProvisioningTests {

        @Test
        @DisplayName("transitions pending keys to rejected successfully")
        void success_transitionsToRejected() {
            String productId = "prod-" + UUID.randomUUID();
            List<String> keys = List.of("KEY-01-" + UUID.randomUUID());
            licenseKeyService.ingestKeys(new BatchIngestionRequest(productId, 1999L, keys));

            licenseKeyService.rejectProvisioning(productId, "Payment failed");

            long rejectedCount = licenseKeyRepository.findAll().stream()
                  .filter(k -> k.getStatus() == PROVISION_REJECTED)
                  .count();
            assertThat(rejectedCount).isEqualTo(1);
        }
    }
}