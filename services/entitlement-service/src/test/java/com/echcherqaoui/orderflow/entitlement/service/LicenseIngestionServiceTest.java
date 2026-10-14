package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionResponse;
import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateKeysException;
import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LicenseIngestionServiceTest {

    @Mock
    private LicenseKeyRepository licenseKeyRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private LicenseIngestionService licenseIngestionService;

    private final String productId = "prod-123";
    private final Long priceCents = 1999L;

    @Nested
    @DisplayName("ingestKeys()")
    class IngestKeys {

        @Test
        @DisplayName("null request throws NullPointerException")
        void nullRequest_throwsNullPointerException() {
            assertThatThrownBy(() -> licenseIngestionService.ingestKeys(null))
                  .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("duplicate keys within the same payload throw DuplicateKeysException")
        void duplicateKeysInPayload_throwsDuplicateKeysException() {
            List<String> keys = List.of("KEY-01", "KEY-02", "KEY-01");
            BatchIngestionRequest request = new BatchIngestionRequest(productId, priceCents, keys);

            assertThatThrownBy(() -> licenseIngestionService.ingestKeys(request))
                  .isInstanceOf(DuplicateKeysException.class);

            then(licenseKeyRepository).shouldHaveNoInteractions();
            then(outboxWriter).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("keys already existing in database throw DuplicateKeysException")
        void existingKeysInDatabase_throwsDuplicateKeysException() {
            List<String> keys = List.of("KEY-01", "KEY-02");
            BatchIngestionRequest request = new BatchIngestionRequest(productId, priceCents, keys);

            given(licenseKeyRepository.findExistingKeyCodes(keys)).willReturn(List.of("KEY-01"));

            assertThatThrownBy(() -> licenseIngestionService.ingestKeys(request))
                  .isInstanceOf(DuplicateKeysException.class);

            then(licenseKeyRepository).should().findExistingKeyCodes(keys);
            then(licenseKeyRepository).should(org.mockito.Mockito.never()).saveAll(anyList());
            then(outboxWriter).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("valid request saves keys, publishes outbox event, and returns response")
        void validRequest_savesKeysAndPublishesOutboxEvent() {
            List<String> keys = List.of("KEY-01", "KEY-02");
            BatchIngestionRequest request = new BatchIngestionRequest(productId, priceCents, keys);

            given(licenseKeyRepository.findExistingKeyCodes(keys)).willReturn(List.of());

            BatchIngestionResponse response = licenseIngestionService.ingestKeys(request);

            assertThat(response).isNotNull();
            assertThat(response.productId()).isEqualTo(productId);
            assertThat(response.keysAdded()).isEqualTo(2);

            then(licenseKeyRepository).should().findExistingKeyCodes(keys);
            then(licenseKeyRepository).should().saveAll(org.mockito.ArgumentMatchers.anyList());
            then(outboxWriter).should().publishDigitalStockProvisionedEvent(productId, priceCents, 2);
        }
    }
}