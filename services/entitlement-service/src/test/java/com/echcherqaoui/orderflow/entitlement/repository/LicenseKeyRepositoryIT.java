package com.echcherqaoui.orderflow.entitlement.repository;

import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import com.echcherqaoui.orderflow.entitlement.util.ItemIds;
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

    private String itemName;

    @BeforeEach
    void setUp() {
        licenseKeyRepository.deleteAllInBatch();
        itemName = "prod-" + UUID.randomUUID();
    }

    private LicenseKey newLicenseKey(String keyCode) {
        return new LicenseKey()
              .setItemId(ItemIds.fromName(itemName))
              .setItemName(itemName)
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
                  .setItemId(ItemIds.fromName(itemName))
                  .setItemName(itemName)
                  .setKeyCode(key1)
                  .setStatus(LicenseKeyStatus.PENDING_PROVISION)
                  .setPriceCents(1999L);

            LicenseKey pk2 = new LicenseKey()
                  .setItemId(ItemIds.fromName(itemName))
                  .setItemName(itemName)
                  .setKeyCode(key2)
                  .setStatus(LicenseKeyStatus.PENDING_PROVISION)
                  .setPriceCents(1999L);

            String otherItemName = "other-prod";
            LicenseKey opk = new LicenseKey()
                  .setItemId(ItemIds.fromName(otherItemName))
                  .setItemName(otherItemName)
                  .setKeyCode(otherProductKey)
                  .setStatus(LicenseKeyStatus.PENDING_PROVISION)
                  .setPriceCents(1999L);

            licenseKeyRepository.saveAllAndFlush(List.of(pk1, pk2, opk));
            entityManager.clear();

            int updatedCount = licenseKeyRepository.updateStatusForProduct(
                  itemName,
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
                  itemName,
                  LicenseKeyStatus.PENDING_PROVISION,
                  AVAILABLE
            );

            assertThat(updatedCount).isZero();
        }
    }

    @Nested
    @DisplayName("findFirstByItemIdAndStatusOrderByIdAsc")
    class FindFirstByItemIdAndStatusTests {

        @Test
        @DisplayName("Returns the first available key ordered by ID ascending")
        void findFirst_returnsOldestAvailableKey() {
            UUID itemId = ItemIds.fromName(itemName);

            LicenseKey key1 = newLicenseKey("KEY-FIRST-" + UUID.randomUUID()).setItemId(itemId);
            LicenseKey key2 = newLicenseKey("KEY-SECOND-" + UUID.randomUUID()).setItemId(itemId);

            licenseKeyRepository.saveAllAndFlush(List.of(key1, key2));
            entityManager.clear();

            var found = licenseKeyRepository.findFirstByItemIdAndStatusOrderByIdAsc(itemId, AVAILABLE);

            assertThat(found).isPresent();
            assertThat(found.get().getKeyCode()).isEqualTo(key1.getKeyCode());
        }

        @Test
        @DisplayName("Returns empty when no available keys exist for item")
        void findFirst_noAvailableKeys_returnsEmpty() {
            UUID itemId = ItemIds.fromName(itemName);

            LicenseKey key1 = newLicenseKey("KEY-ASSIGNED-" + UUID.randomUUID())
                  .setItemId(itemId)
                  .setStatus(LicenseKeyStatus.ASSIGNED);

            licenseKeyRepository.saveAndFlush(key1);
            entityManager.clear();

            var found = licenseKeyRepository.findFirstByItemIdAndStatusOrderByIdAsc(itemId, AVAILABLE);

            assertThat(found).isEmpty();
        }
    }
}