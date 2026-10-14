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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LicenseIngestionServiceIT implements WithPostgres {

    @Autowired
    private LicenseIngestionService licenseIngestionService;

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

            BatchIngestionResponse response = licenseIngestionService.ingestKeys(request);

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
            licenseIngestionService.ingestKeys(initialRequest);

            long countBefore = licenseKeyRepository.count();

            // Attempt to ingest batch containing the existing key
            List<String> newKeys = List.of(duplicateKey, "KEY-NEW-" + UUID.randomUUID());
            BatchIngestionRequest requestWithDuplicates = new BatchIngestionRequest(productId, 1999L, newKeys);

            assertThatThrownBy(() -> licenseIngestionService.ingestKeys(requestWithDuplicates))
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

            assertThatThrownBy(() -> licenseIngestionService.ingestKeys(request))
                  .isInstanceOf(DuplicateKeysException.class);

            assertThat(licenseKeyRepository.count()).isZero();
        }
    }
}