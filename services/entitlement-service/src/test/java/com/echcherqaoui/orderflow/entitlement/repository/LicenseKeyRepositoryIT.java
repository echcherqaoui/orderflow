package com.echcherqaoui.orderflow.entitlement.repository;

import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.AVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LicenseKeyRepositoryIT implements WithPostgres {

    @Autowired
    private LicenseKeyRepository licenseKeyRepository;

    @Autowired
    private EntityManager entityManager;

    private String productId;

    @BeforeEach
    void setUp() {
        licenseKeyRepository.deleteAllInBatch();
        productId = "prod-" + UUID.randomUUID();
    }

    private LicenseKey newLicenseKey(String keyCode) {
        return new LicenseKey()
              .setProductId(productId)
              .setKeyCode(keyCode)
              .setStatus(AVAILABLE)
              .setPriceCents(1999L);
    }

    @Nested
    @DisplayName("findExistingKeyCodes")
    class FindExistingKeyCodesTests {

        @Test
        @DisplayName("Returns only the key codes that already exist in the database")
        void findExistingKeyCodes_someExist_returnsExistingOnly() {
            String existingKey1 = "KEY-001-" + UUID.randomUUID();
            String existingKey2 = "KEY-002-" + UUID.randomUUID();
            String nonExistingKey = "KEY-003-" + UUID.randomUUID();

            licenseKeyRepository.saveAndFlush(newLicenseKey(existingKey1));
            licenseKeyRepository.saveAndFlush(newLicenseKey(existingKey2));

            entityManager.clear();

            List<String> queryList = List.of(existingKey1, nonExistingKey, existingKey2);
            List<String> foundKeys = licenseKeyRepository.findExistingKeyCodes(queryList);

            assertThat(foundKeys)
                  .hasSize(2)
                  .containsExactlyInAnyOrder(existingKey1, existingKey2);
        }

        @Test
        @DisplayName("Returns empty list when none of the key codes exist")
        void findExistingKeyCodes_noneExist_returnsEmpty() {
            List<String> queryList = List.of("KEY-NONEXISTENT-1", "KEY-NONEXISTENT-2");
            List<String> foundKeys = licenseKeyRepository.findExistingKeyCodes(queryList);

            assertThat(foundKeys).isEmpty();
        }

        @Test
        @DisplayName("Returns empty list when input list is empty")
        void findExistingKeyCodes_emptyInput_returnsEmpty() {
            List<String> foundKeys = licenseKeyRepository.findExistingKeyCodes(List.of());

            assertThat(foundKeys).isEmpty();
        }
    }

    @Nested
    @DisplayName("updateStatusForProduct")
    class UpdateStatusForProductTests {

        @Test
        @DisplayName("Updates keys matching product and old status, returns correct count")
        void updateStatusForProduct_matchingKeys_updatesSuccessfully() {
            String key1 = "KEY-PENDING-1-" + UUID.randomUUID();
            String key2 = "KEY-PENDING-2-" + UUID.randomUUID();
            String otherProductKey = "KEY-OTHER-1-" + UUID.randomUUID();

            LicenseKey pk1 = new LicenseKey()
                  .setProductId(productId)
                  .setKeyCode(key1)
                  .setStatus(LicenseKeyStatus.PENDING_PROVISION)
                  .setPriceCents(1999L);

            LicenseKey pk2 = new LicenseKey()
                  .setProductId(productId)
                  .setKeyCode(key2)
                  .setStatus(LicenseKeyStatus.PENDING_PROVISION)
                  .setPriceCents(1999L);

            LicenseKey opk = new LicenseKey()
                  .setProductId("other-prod")
                  .setKeyCode(otherProductKey)
                  .setStatus(LicenseKeyStatus.PENDING_PROVISION)
                  .setPriceCents(1999L);

            licenseKeyRepository.saveAllAndFlush(List.of(pk1, pk2, opk));
            entityManager.clear();

            int updatedCount = licenseKeyRepository.updateStatusForProduct(
                  productId,
                  LicenseKeyStatus.PENDING_PROVISION,
                  AVAILABLE
            );

            assertThat(updatedCount).isEqualTo(2);

            LicenseKey refreshed1 = licenseKeyRepository.findById(pk1.getId()).orElseThrow();
            assertThat(refreshed1.getStatus()).isEqualTo(AVAILABLE);

            LicenseKey refreshedOther = licenseKeyRepository.findById(opk.getId()).orElseThrow();
            assertThat(refreshedOther.getStatus()).isEqualTo(LicenseKeyStatus.PENDING_PROVISION);
        }

        @Test
        @DisplayName("Returns zero when no keys match product and old status")
        void updateStatusForProduct_noMatch_returnsZero() {
            int updatedCount = licenseKeyRepository.updateStatusForProduct(
                  productId,
                  LicenseKeyStatus.PENDING_PROVISION,
                  AVAILABLE
            );

            assertThat(updatedCount).isZero();
        }
    }
}